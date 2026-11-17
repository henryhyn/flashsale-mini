package flashsale.order;

import flashsale.common.Result;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** m4 对照实验用的接口 (scripts/verify-m4.sh 调用)，正式部署时应该去掉 */
@RestController
@RequestMapping("/lab/order")
public class OrderLabController {
    private final OrderConsumer consumer;
    private final TimeoutConsumer timeouts;
    private final AsyncSeckillService seckill;
    private final OrderService orders;
    private final ReconcileService reconcile;

    public OrderLabController(OrderConsumer consumer, TimeoutConsumer timeouts, AsyncSeckillService seckill,
                              OrderService orders, ReconcileService reconcile) {
        this.consumer = consumer;
        this.timeouts = timeouts;
        this.seckill = seckill;
        this.orders = orders;
        this.reconcile = reconcile;
    }

    /**
     * mode：消费者的写法 (correct、ackFirst、noRetry、notIdempotent)；processDelayMs：每条消息的模拟处理耗时；
     * failSeconds：接下来多少秒内落库都失败 (模拟数据库不可用)；sendTwice：每条下单消息发两次；payTimeoutSeconds：支付超时
     */
    @PostMapping("/config")
    public Result<Void> config(@RequestParam(defaultValue = "correct") OrderConsumer.Mode mode,
                               @RequestParam(defaultValue = "0") int processDelayMs,
                               @RequestParam(defaultValue = "0") int failSeconds,
                               @RequestParam(defaultValue = "false") boolean sendTwice,
                               @RequestParam(defaultValue = "900") int payTimeoutSeconds) {
        consumer.configure(mode, processDelayMs, failSeconds);
        seckill.setSendTwice(sendTwice);
        orders.setPayTimeout(Duration.ofSeconds(payTimeoutSeconds));
        timeouts.cancelled.set(0);
        return Result.ok(null);
    }

    @GetMapping("/stats")
    public Result<Map<String, Long>> stats() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("created", consumer.created.get());
        m.put("duplicates", consumer.duplicates.get());
        m.put("failures", consumer.failures.get());
        m.put("cancelled", timeouts.cancelled.get());
        return Result.ok(m);
    }

    @PostMapping("/reconcile")
    public Result<Integer> reconcile(@RequestParam long voucherId, @RequestParam(defaultValue = "0") int olderThanSeconds) {
        return Result.ok(reconcile.reconcile(voucherId, Duration.ofSeconds(olderThanSeconds)));
    }
}
