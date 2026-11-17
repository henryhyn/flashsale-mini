package flashsale.order;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 下单消息的消费者。正式写法 (correct)：落库成功才返回 CONSUME_SUCCESS (确认)；落库失败返回 RECONSUME_LATER，
 * 消息稍后重新投递，失败超过 maxReconsumeTimes 次进入死信队列；重复投递的消息靠订单号幂等。
 * 另外三种模式是对照实验用的错误写法，由 /lab/order/config 切换。
 */
@Component
public class OrderConsumer implements InitializingBean, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);

    public enum Mode {
        /** 正式写法 */
        correct,
        /** 先确认后处理：收到消息交给线程池就返回成功，进程崩溃时线程池里的消息就丢了 */
        ackFirst,
        /** 不重试：落库失败只打日志，照样返回成功 */
        noRetry,
        /** 不幂等：消费者自己生成订单号 */
        notIdempotent
    }

    private final OrderService orders;
    private final JsonMapper json;
    private final DefaultMQPushConsumer consumer;
    private final ExecutorService ackFirstPool = Executors.newFixedThreadPool(4);

    // 实验用的开关和计数
    private volatile Mode mode = Mode.correct;
    private volatile int processDelayMs;
    private volatile long failUntil;
    final AtomicLong created = new AtomicLong(), duplicates = new AtomicLong(), failures = new AtomicLong();

    public OrderConsumer(OrderService orders, JsonMapper json, @Value("${rocketmq.name-server}") String nameServer,
                         @Value("${flashsale.order.consume-threads:4}") int threads) {
        this.orders = orders;
        this.json = json;
        consumer = new DefaultMQPushConsumer("seckill-order-consumer");
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeThreadMin(threads);
        consumer.setConsumeThreadMax(threads);
        consumer.setMaxReconsumeTimes(3);
        // 消费进度默认每 5 秒提交一次；实验里改成 1 秒，让“先确认后处理”的丢消息更容易复现
        consumer.setPersistConsumerOffsetInterval(1000);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        consumer.subscribe(MqConfig.ORDER_TOPIC, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt msg : msgs) {
                OrderMessage m = json.readValue(msg.getBody(), OrderMessage.class);
                if (mode == Mode.ackFirst) {
                    ackFirstPool.submit(() -> {
                        process(m);
                        return null;
                    });
                    continue;
                }
                try {
                    process(m);
                } catch (DuplicateKeyException e) {
                    duplicates.incrementAndGet();   // 订单号已经存在：这条消息处理过了，直接确认
                } catch (Exception e) {
                    failures.incrementAndGet();
                    if (mode == Mode.noRetry) {
                        log.warn("订单 {} 落库失败，不重试", m.orderId());
                        continue;
                    }
                    log.warn("订单 {} 落库失败 (第 {} 次重试)，稍后重新投递：{}", m.orderId(), msg.getReconsumeTimes(), e.getMessage());
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }

    private void process(OrderMessage m) throws InterruptedException {
        if (processDelayMs > 0) Thread.sleep(processDelayMs);   // 模拟处理耗时
        if (System.currentTimeMillis() < failUntil) throw new IllegalStateException("模拟数据库不可用");
        if (mode == Mode.notIdempotent) {
            orders.createNotIdempotent(m);
        } else {
            orders.create(m);
        }
        created.incrementAndGet();
    }

    @Override
    public void destroy() {
        consumer.shutdown();
        ackFirstPool.shutdownNow();
    }

    public void configure(Mode mode, int processDelayMs, int failSeconds) {
        this.mode = mode;
        this.processDelayMs = processDelayMs;
        this.failUntil = failSeconds > 0 ? System.currentTimeMillis() + failSeconds * 1000L : 0;
        created.set(0);
        duplicates.set(0);
        failures.set(0);
    }
}
