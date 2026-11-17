package flashsale.order;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MqConfig {
    public static final String ORDER_TOPIC = "seckill-order", TIMEOUT_TOPIC = "order-timeout";

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer producer(@Value("${rocketmq.name-server}") String nameServer) {
        DefaultMQProducer producer = new DefaultMQProducer("flashsale-producer");
        producer.setNamesrvAddr(nameServer);
        // 同步发送失败时自动重试 2 次 (默认值)：重试可能让同一条消息发出两次，所以消费者必须幂等
        producer.setRetryTimesWhenSendFailed(2);
        return producer;
    }
}
