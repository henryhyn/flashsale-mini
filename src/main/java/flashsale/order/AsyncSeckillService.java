package flashsale.order;

import flashsale.common.BizException;
import flashsale.common.IdGenerator;
import flashsale.voucher.LuaSeckillService;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** m4：Redis 判断资格后发一条下单消息就返回，由消费者异步落库 */
@Service
public class AsyncSeckillService {
    private static final Logger log = LoggerFactory.getLogger(AsyncSeckillService.class);

    private final LuaSeckillService lua;
    private final DefaultMQProducer producer;
    private final IdGenerator ids;
    private final JsonMapper json;
    /** 实验用：每条消息发两次，模拟生产者重试、网络抖动造成的重复投递 */
    private volatile boolean sendTwice;

    public AsyncSeckillService(LuaSeckillService lua, DefaultMQProducer producer, IdGenerator ids, JsonMapper json) {
        this.lua = lua;
        this.producer = producer;
        this.ids = ids;
        this.json = json;
    }

    public long seckill(long userId, long voucherId) {
        long orderId = ids.nextId();
        lua.reserve(userId, voucherId, orderId);
        Message msg = new Message(MqConfig.ORDER_TOPIC, json.writeValueAsBytes(new OrderMessage(orderId, userId, voucherId)));
        msg.setKeys(String.valueOf(orderId));   // 按订单号建索引，排查问题时可以在控制台按订单号查消息
        try {
            // 发送失败会抛异常。返回 SEND_OK 以外的状态 (比如刷盘超时) 时消息已经到了 broker，不能当作失败回滚
            SendStatus status = producer.send(msg).getSendStatus();
            if (status != SendStatus.SEND_OK) log.warn("订单 {} 的下单消息发送状态：{}", orderId, status);
            if (sendTwice) producer.send(msg);
        } catch (Exception e) {
            // 消息没发出去：把 Redis 里的资格还回去，告诉用户稍后再试
            log.error("订单 {} 的下单消息发送失败", orderId, e);
            lua.release(userId, voucherId, true);
            throw new BizException(503, "系统繁忙，请稍后再试");
        }
        return orderId;   // 此时订单还在排队，客户端用订单号查询结果
    }

    public void setSendTwice(boolean sendTwice) {
        this.sendTwice = sendTwice;
    }
}
