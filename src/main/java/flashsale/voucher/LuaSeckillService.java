package flashsale.voucher;

import flashsale.common.BizException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * m3：Redis 秒杀。资格判断 (库存、一人一单) 和预扣库存由一个 Lua 脚本原子地完成，
 * 只有拿到资格的请求才去数据库下单。Redis 单线程执行脚本，脚本之间不会交错，所以不需要分布式锁。
 */
@Service
public class LuaSeckillService {
    private static final RedisScript<Long> SECKILL = RedisScript.of(new ClassPathResource("lua/seckill.lua"), Long.class);
    private static final RedisScript<Long> ROLLBACK = RedisScript.of(new ClassPathResource("lua/seckill_rollback.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final SeckillService orders;
    /**
     * 售罄标记：本实例已经知道卖完了的券，之后的请求连 Redis 都不用访问。
     * 标记只保留 1 秒：库存可能被加回来 (下单失败回滚、重新上架)，而别的实例不会通知这里，
     * 永久的标记会让这个实例一直拒绝，造成少卖。
     */
    private final Cache<Long, Boolean> soldOut = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(1)).build();

    public LuaSeckillService(StringRedisTemplate redis, SeckillService orders) {
        this.redis = redis;
        this.orders = orders;
    }

    /** 两个 key 用同一个 hash tag {voucherId}：换成 Redis Cluster 时，一个脚本访问的 key 必须在同一个槽里 */
    static String stockKey(long voucherId) { return "seckill:stock:{" + voucherId + "}"; }

    static String usersKey(long voucherId) { return "seckill:users:{" + voucherId + "}"; }

    public long seckill(long userId, long voucherId) {
        return seckill(userId, voucherId, false);
    }

    /** naiveTable 为 true 时订单写进没有唯一索引的演示表 (对照实验用) */
    public long seckill(long userId, long voucherId, boolean naiveTable) {
        if (soldOut.getIfPresent(voucherId) != null) throw new BizException(1001, "已抢光");
        List<String> keys = List.of(stockKey(voucherId), usersKey(voucherId));
        long r = redis.execute(SECKILL, keys, String.valueOf(userId));
        if (r == 1) {
            soldOut.put(voucherId, true);
            throw new BizException(1001, "已抢光");
        }
        if (r == 2) throw new BizException(1002, "不能重复下单");
        if (r == 3) throw new BizException(1003, "活动还没有开始");
        try {
            return naiveTable ? orders.createNaive(userId, voucherId) : orders.safe(userId, voucherId);
        } catch (RuntimeException e) {
            // 落库失败：把预扣的库存还回去。数据库说重复下单时，这个用户确实买过，留在集合里
            boolean duplicate = e instanceof BizException b && b.code() == 1002;
            redis.execute(ROLLBACK, keys, String.valueOf(userId), duplicate ? "0" : "1");
            throw e;
        }
    }

    /** 活动开始前把库存加载进 Redis (预热)，清掉已下单用户和本实例的售罄标记 (其他实例的标记 1 秒后自然失效) */
    public void preheat(long voucherId, int stock) {
        redis.delete(usersKey(voucherId));
        redis.opsForValue().set(stockKey(voucherId), String.valueOf(stock));
        soldOut.invalidate(voucherId);
    }
}
