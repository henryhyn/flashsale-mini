package flashsale.order;

import flashsale.common.BizException;
import flashsale.common.IdGenerator;
import flashsale.voucher.LuaSeckillService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** m4：订单落库、支付、超时取消 */
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    static final int UNPAID = 0, PAID = 1, CANCELLED = 2;

    private final OrderMapper mapper;
    private final DefaultMQProducer producer;
    private final LuaSeckillService lua;
    private final IdGenerator ids;
    private volatile Duration payTimeout;

    public OrderService(OrderMapper mapper, DefaultMQProducer producer, LuaSeckillService lua, IdGenerator ids,
                        @Value("${flashsale.order.pay-timeout:15m}") Duration payTimeout) {
        this.mapper = mapper;
        this.producer = producer;
        this.lua = lua;
        this.ids = ids;
        this.payTimeout = payTimeout;
    }

    /**
     * 消费者落库 (幂等)：先插订单，再扣库存。同一条消息重复投递时，订单号 (主键) 冲突，
     * 插入直接失败，库存还没扣，调用方把 DuplicateKeyException 当作“已经处理过”。
     * 提交以后发一条定时消息，到期时检查是否支付。
     */
    @Transactional
    public void create(OrderMessage m) {
        mapper.insert(m.orderId(), m.userId(), m.voucherId());
        if (mapper.deductStock(m.voucherId()) == 0) {
            // Redis 预扣成功、数据库却没有库存：两边对不上，回滚并让消息重试 (多次失败后进死信队列，人工处理)
            throw new IllegalStateException("数据库库存不足，订单 " + m.orderId());
        }
        afterCommit(() -> sendTimeoutCheck(m.orderId()));
    }

    /** 错误写法 (对照组)：消费者自己生成订单号，同一条消息投递两次就生成两个订单 */
    @Transactional
    public void createNotIdempotent(OrderMessage m) {
        if (mapper.deductStock(m.voucherId()) == 0) throw new IllegalStateException("数据库库存不足");
        mapper.insertNaive(ids.nextId(), m.userId(), m.voucherId());
    }

    public void pay(long orderId, long userId) {
        VoucherOrder o = mapper.find(orderId);
        if (o == null || o.userId() != userId) throw new BizException(1005, "订单不存在");
        if (mapper.changeStatus(orderId, UNPAID, PAID) == 0) throw new BizException(1006, "订单已支付或已取消");
    }

    /** 超时取消：只有待支付的订单才取消；取消后库存还给数据库和 Redis，这个用户可以重新抢 */
    @Transactional
    public boolean cancelIfUnpaid(long orderId) {
        VoucherOrder o = mapper.find(orderId);
        if (o == null || mapper.changeStatus(orderId, UNPAID, CANCELLED) == 0) return false;
        mapper.restoreStock(o.voucherId());
        afterCommit(() -> lua.release(o.userId(), o.voucherId(), true));
        return true;
    }

    public VoucherOrder find(long orderId) {
        return mapper.find(orderId);
    }

    private void sendTimeoutCheck(long orderId) {
        Message msg = new Message(MqConfig.TIMEOUT_TOPIC, String.valueOf(orderId).getBytes(StandardCharsets.UTF_8));
        msg.setDeliverTimeMs(System.currentTimeMillis() + payTimeout.toMillis());   // RocketMQ 5 的定时消息，精确到毫秒
        try {
            producer.send(msg);
        } catch (Exception e) {
            // 发送失败时订单不会自动取消；可以由定时任务扫描超时未支付的订单兜底 (见 README 的扩展方向)
            log.error("订单 {} 的超时检查消息发送失败", orderId, e);
        }
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    public void setPayTimeout(Duration payTimeout) {
        this.payTimeout = payTimeout;
    }
}
