package flashsale.order;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 订单超时检查：定时消息到期后投递到这里，订单还没支付就取消 */
@Component
public class TimeoutConsumer implements InitializingBean, DisposableBean {
    private final OrderService orders;
    private final DefaultMQPushConsumer consumer;
    final AtomicLong cancelled = new AtomicLong();

    public TimeoutConsumer(OrderService orders, @Value("${rocketmq.name-server}") String nameServer) {
        this.orders = orders;
        consumer = new DefaultMQPushConsumer("order-timeout-consumer");
        consumer.setNamesrvAddr(nameServer);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        consumer.subscribe(MqConfig.TIMEOUT_TOPIC, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            try {
                for (MessageExt msg : msgs) {
                    // 已支付或已取消的订单 cancelIfUnpaid 什么也不做，所以重复投递也没关系
                    if (orders.cancelIfUnpaid(Long.parseLong(new String(msg.getBody(), StandardCharsets.UTF_8)))) cancelled.incrementAndGet();
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (Exception e) {
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });
        consumer.start();
    }

    @Override
    public void destroy() {
        consumer.shutdown();
    }
}
