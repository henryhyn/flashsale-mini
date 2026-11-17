package flashsale.voucher;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import flashsale.common.BizException;
import flashsale.common.IdGenerator;
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
    private final IdGenerator ids;
    /**
     * 售罄标记：本实例已经知道卖完了的券，之后的请求连 Redis 都不用访问。
     * 标记只保留 1 秒：库存可能被加回来 (下单失败回滚、订单超时取消、重新上架)，而别的实例不会通知这里，
     * 永久的标记会让这个实例一直拒绝，造成少卖。
     */
    private final Cache<Long, Boolean> soldOut = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(1)).build();

    public LuaSeckillService(StringRedisTemplate redis, SeckillService orders, IdGenerator ids) {
        this.redis = redis;
        this.orders = orders;
        this.ids = ids;
    }

    /** 两个 key 用同一个 hash tag {voucherId}：换成 Redis Cluster 时，一个脚本访问的 key 必须在同一个槽里 */
    public static String stockKey(long voucherId) { return "seckill:stock:{" + voucherId + "}"; }

    public static String usersKey(long voucherId) { return "seckill:users:{" + voucherId + "}"; }

    /** m3 的正式写法：Redis 判断资格，再同步落库 */
    public long seckill(long userId, long voucherId) {
        return seckill(userId, voucherId, false);
    }

    /** naiveTable 为 true 时订单写进没有唯一索引的演示表 (对照实验用) */
    public long seckill(long userId, long voucherId, boolean naiveTable) {
        long orderId = ids.nextId();
        reserve(userId, voucherId, orderId);
        try {
            return naiveTable ? orders.createNaive(orderId, userId, voucherId) : orders.safe(orderId, userId, voucherId);
        } catch (RuntimeException e) {
            // 落库失败：把预扣的库存还回去。数据库说重复下单时，这个用户确实买过，留在 Hash 里
            release(userId, voucherId, !(e instanceof BizException b && b.code() == 1002));
            throw e;
        }
    }

    /** 抢资格：成功时 Redis 里已经预扣了库存、记下了“用户 → 订单号”；失败时抛出业务异常 */
    public void reserve(long userId, long voucherId, long orderId) {
        if (soldOut.getIfPresent(voucherId) != null) throw new BizException(1001, "已抢光");
        long r = redis.execute(SECKILL, keys(voucherId), String.valueOf(userId), String.valueOf(orderId));
        if (r == 1) {
            soldOut.put(voucherId, true);
            throw new BizException(1001, "已抢光");
        }
        if (r == 2) throw new BizException(1002, "不能重复下单");
        if (r == 3) throw new BizException(1003, "活动还没有开始");
    }

    /** 还回预扣的库存；removeUser 为 true 时把用户移出 Hash，允许他再买 */
    public void release(long userId, long voucherId, boolean removeUser) {
        redis.execute(ROLLBACK, keys(voucherId), String.valueOf(userId), removeUser ? "1" : "0");
    }

    private static List<String> keys(long voucherId) {
        return List.of(stockKey(voucherId), usersKey(voucherId));
    }

    /** 活动开始前把库存加载进 Redis (预热)，清掉已下单用户和本实例的售罄标记 (其他实例的标记 1 秒后自然失效) */
    public void preheat(long voucherId, int stock) {
        redis.delete(usersKey(voucherId));
        redis.opsForValue().set(stockKey(voucherId), String.valueOf(stock));
        soldOut.invalidate(voucherId);
    }
}
