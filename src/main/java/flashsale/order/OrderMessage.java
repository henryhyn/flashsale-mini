package flashsale.order;

/** 下单消息：订单号在发消息之前就生成好了，消费者用它做幂等 */
public record OrderMessage(long orderId, long userId, long voucherId) { }
