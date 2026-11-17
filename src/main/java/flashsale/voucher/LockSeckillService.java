package flashsale.voucher;

import flashsale.common.BizException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * m3 的对照组：用锁保证一人一单 (“先查是否买过，再下单”这两步不能被同一用户的另一个请求插进来)。
 * 拿不到锁就说明这个用户有另一个请求正在下单，直接拒绝。
 */
@Service
public class LockSeckillService {
    private final SeckillService orders;
    private final RedissonClient redisson;
    /** 每个用户一把锁。用 ReentrantLock 而不是 synchronized：JDK 21 的虚拟线程在 synchronized 里阻塞会钉住载体线程 */
    private final ConcurrentHashMap<Long, Lock> userLocks = new ConcurrentHashMap<>();

    public LockSeckillService(SeckillService orders, RedissonClient redisson) {
        this.orders = orders;
        this.redisson = redisson;
    }

    /** 本地锁：只在一个 JVM 里有效，部署多个实例时同一用户的请求落到不同实例，锁就失效了 */
    public long jvmLock(long userId, long voucherId) {
        Lock lock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock());
        if (!lock.tryLock()) throw new BizException(1002, "不能重复下单");
        try {
            return orders.checkThenCreateNaive(userId, voucherId);
        } finally {
            lock.unlock();
        }
    }

    /** Redisson 分布式锁：锁在 Redis 里，所有实例共享；没有指定持有时间时，看门狗每 10 秒把锁续期到 30 秒 */
    public long redissonLock(long userId, long voucherId) {
        RLock lock = redisson.getLock("lock:order:" + userId);
        if (!lock.tryLock()) throw new BizException(1002, "不能重复下单");
        try {
            return orders.checkThenCreateNaive(userId, voucherId);
        } finally {
            lock.unlock();
        }
    }
}
