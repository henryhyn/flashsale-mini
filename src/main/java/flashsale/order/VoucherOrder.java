package flashsale.order;

/** status：0 待支付，1 已支付，2 已取消 */
public record VoucherOrder(long id, long userId, long voucherId, int status) { }
