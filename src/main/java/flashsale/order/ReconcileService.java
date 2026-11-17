package flashsale.order;

import flashsale.common.IdGenerator;
import flashsale.voucher.LuaSeckillService;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 少卖对账：Redis 里记着“用户 → 订单号”(拿到了资格)，数据库里却没有这张订单，说明下单消息丢了或者处理失败了。
 * 只看生成时间早于阈值的订单 (订单号里带着时间戳)，避免把还在队列里排队的消息当成丢失。
 * 补单用和消费者同一个幂等方法：补单时消息恰好又到了，也只会有一张订单。
 */
@Service
public class ReconcileService {
    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);
    private final StringRedisTemplate redis;
    private final OrderMapper mapper;
    private final OrderService orders;

    public ReconcileService(StringRedisTemplate redis, OrderMapper mapper, OrderService orders) {
        this.redis = redis;
        this.mapper = mapper;
        this.orders = orders;
    }

    /** 每分钟对一次账，只补 1 分钟以前就该落库的订单 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void scheduled() {
        for (String key : redis.keys("seckill:users:*")) {
            long voucherId = Long.parseLong(key.replaceAll("\\D", ""));
            int repaired = reconcile(voucherId, Duration.ofMinutes(1));
            if (repaired > 0) log.warn("秒杀券 {} 对账补单 {} 张", voucherId, repaired);
        }
    }

    /** 返回补单的数量 */
    public int reconcile(long voucherId, Duration olderThan) {
        Map<Object, Object> reserved = redis.opsForHash().entries(LuaSeckillService.usersKey(voucherId));
        Set<Long> inDb = new HashSet<>(mapper.findIds(voucherId));
        long cutoff = System.currentTimeMillis() - olderThan.toMillis();
        int repaired = 0;
        for (var e : reserved.entrySet()) {
            long orderId = Long.parseLong((String) e.getValue());
            if (inDb.contains(orderId) || IdGenerator.timestampOf(orderId) > cutoff) continue;
            try {
                orders.create(new OrderMessage(orderId, Long.parseLong((String) e.getKey()), voucherId));
                repaired++;
            } catch (DuplicateKeyException ignored) {
                // 刚刚被消费者落库了
            }
        }
        return repaired;
    }
}
