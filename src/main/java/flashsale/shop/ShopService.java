package flashsale.shop;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import flashsale.common.BizException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * 商户详情的几种缓存读法和更新写法。正式接口用 {@link #get} 和 {@link #updateAvgPrice}，
 * 其他方法是对照实验用的“错误写法”或“另一种写法”。
 */
@Service
public class ShopService {
    private static final Logger log = LoggerFactory.getLogger(ShopService.class);
    static final String KEY = "cache:shop:", LOGICAL_KEY = "cache:shop:logical:", LOCK_KEY = "lock:shop:";
    static final Duration TTL = Duration.ofMinutes(30), NULL_TTL = Duration.ofMinutes(2), LOCK_TTL = Duration.ofSeconds(10);
    static final Duration LOGICAL_TTL = Duration.ofMinutes(30);
    /** 只删自己加的锁：比较 value 和删除要在一个 Lua 脚本里原子地做 */
    private static final RedisScript<Long> UNLOCK = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final ShopMapper mapper;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    /** 本地缓存：挡在 Redis 前面扛热 key，过期时间很短，因为各实例之间的本地缓存不会互相通知 */
    private final Cache<Long, Shop> local = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(5)).build();
    private final ExecutorService rebuildPool = Executors.newVirtualThreadPerTaskExecutor();

    // 实验用：数据库查询次数、模拟的查询耗时、模拟的事务里其他操作的耗时。
    // 外部只能通过方法访问：这个类有 @Transactional，注入到别处的是 CGLIB 代理，代理对象上的字段都是 null
    private final AtomicLong dbQueries = new AtomicLong();
    private volatile int queryDelayMs, txWorkMs;

    public ShopService(ShopMapper mapper, StringRedisTemplate redis, JsonMapper json) {
        this.mapper = mapper;
        this.redis = redis;
        this.json = json;
    }

    // ---------- 读 ----------

    /** 推荐写法：旁路缓存 + 空值缓存防穿透 + 互斥锁防击穿 */
    public Shop get(long id) {
        for (int retry = 0; retry < 100; retry++) {
            String v = redis.opsForValue().get(KEY + id);
            if (v != null) return parse(v);
            String token = UUID.randomUUID().toString();
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY + id, token, LOCK_TTL))) {
                try {
                    v = redis.opsForValue().get(KEY + id);   // 拿到锁以后再查一次：可能别人刚重建完
                    if (v != null) return parse(v);
                    Shop shop = loadFromDb(id);
                    // 查不到的 id 也缓存一个空串 (过期时间短一些)，挡住反复查不存在的 id 的请求
                    redis.opsForValue().set(KEY + id, shop == null ? "" : json.writeValueAsString(shop), shop == null ? NULL_TTL : ttl());
                    return orNotFound(shop);
                } finally {
                    redis.execute(UNLOCK, List.of(LOCK_KEY + id), token);
                }
            }
            sleep(20);   // 没抢到锁：等一会儿再查缓存 (虚拟线程里 sleep 不占平台线程)
        }
        throw new BizException(503, "系统繁忙，请稍后再试");
    }

    /** 对照组：直接查数据库 */
    public Shop db(long id) {
        return orNotFound(loadFromDb(id));
    }

    /** 对照组：最朴素的旁路缓存。查不到的 id 不缓存，热 key 过期时也不加保护 */
    public Shop aside(long id) {
        String v = redis.opsForValue().get(KEY + id);
        if (v != null) return parse(v);
        Shop shop = orNotFound(loadFromDb(id));
        redis.opsForValue().set(KEY + id, json.writeValueAsString(shop), ttl());
        return shop;
    }

    /**
     * 另一种防击穿的写法：逻辑过期。Redis 里的 key 不设过期时间，过期时间写在 value 里；
     * 发现过期的请求抢锁，抢到的交给后台线程重建，所有请求 (包括它自己) 先返回旧数据。
     * 前提是提前预热：key 不存在时这里直接查库，不做保护。
     */
    public Shop logical(long id) {
        String v = redis.opsForValue().get(LOGICAL_KEY + id);
        if (v == null) return orNotFound(rebuildLogical(id, LOGICAL_TTL));
        LogicalValue lv = json.readValue(v, LogicalValue.class);
        if (lv.expireAt() > System.currentTimeMillis()) return orNotFound(lv.data());
        String token = UUID.randomUUID().toString();
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY + id, token, LOCK_TTL))) {
            rebuildPool.submit(() -> {
                try {
                    rebuildLogical(id, LOGICAL_TTL);
                } finally {
                    redis.execute(UNLOCK, List.of(LOCK_KEY + id), token);
                }
            });
        }
        return orNotFound(lv.data());
    }

    record LogicalValue(Shop data, long expireAt) { }

    public Shop rebuildLogical(long id, Duration ttl) {
        Shop shop = loadFromDb(id);
        redis.opsForValue().set(LOGICAL_KEY + id, json.writeValueAsString(new LogicalValue(shop, System.currentTimeMillis() + ttl.toMillis())));
        return shop;
    }

    /** 两级缓存：本地缓存 (Caffeine) → Redis → 数据库 */
    public Shop twoLevel(long id) {
        return local.get(id, this::get);
    }

    // ---------- 写 ----------

    /** 推荐写法：先更新数据库，事务提交以后再删缓存，删除失败就重试 */
    @Transactional
    public void updateAvgPrice(long id, int avgPrice) {
        mapper.updateAvgPrice(id, avgPrice);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictWithRetry(id);
            }
        });
        otherWorkInTx();
    }

    /** 错误写法：先删缓存，再更新数据库 */
    @Transactional
    public void updateDeleteFirst(long id, int avgPrice) {
        evict(id);
        mapper.updateAvgPrice(id, avgPrice);
        otherWorkInTx();
    }

    /** 错误写法：先更新数据库再删缓存，但删除发生在事务提交之前 */
    @Transactional
    public void updateDeleteInTx(long id, int avgPrice) {
        mapper.updateAvgPrice(id, avgPrice);
        evict(id);
        otherWorkInTx();
    }

    /** 删除失败 (比如 Redis 短暂不可用) 时在后台重试几次；都失败就只能等过期时间兜底 */
    void evictWithRetry(long id) {
        try {
            evict(id);
        } catch (RuntimeException e) {
            rebuildPool.submit(() -> {
                for (int i = 1; i <= 3; i++) {
                    sleep(100L << i);
                    try {
                        evict(id);
                        return;
                    } catch (RuntimeException again) {
                        log.warn("删除商户 {} 的缓存第 {} 次重试失败", id, i, again);
                    }
                }
                log.error("删除商户 {} 的缓存失败，等过期时间兜底", id);
            });
        }
    }

    void evict(long id) {
        redis.delete(List.of(KEY + id, LOGICAL_KEY + id));
        local.invalidate(id);
    }

    // ---------- 工具 ----------

    Shop loadFromDb(long id) {
        dbQueries.incrementAndGet();
        return mapper.findById(id, queryDelayMs / 1000.0);
    }

    /** 过期时间加一点随机数，避免一批同时写入的 key 同时过期 (缓存雪崩) */
    private static Duration ttl() {
        return TTL.plusSeconds(ThreadLocalRandom.current().nextInt(300));
    }

    private Shop parse(String v) {
        return v.isEmpty() ? orNotFound(null) : json.readValue(v, Shop.class);
    }

    private static Shop orNotFound(Shop shop) {
        if (shop == null) throw new BizException(1004, "商户不存在");
        return shop;
    }

    /** 模拟事务里的其他操作 (写操作日志、发通知……)，它们让事务持续得更久 */
    private void otherWorkInTx() {
        if (txWorkMs > 0) sleep(txWorkMs);
    }

    public void clearLocal() {
        local.invalidateAll();
    }

    public void configure(int queryDelayMs, int txWorkMs) {
        this.queryDelayMs = queryDelayMs;
        this.txWorkMs = txWorkMs;
        dbQueries.set(0);
    }

    public long dbQueries() {
        return dbQueries.get();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
