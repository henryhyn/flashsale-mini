package flashsale.order;

import flashsale.common.BizException;
import flashsale.common.Result;
import flashsale.common.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
    private static final String[] STATUS = {"待支付", "已支付", "已取消"};
    private final OrderService orders;

    public OrderController(OrderService orders) { this.orders = orders; }

    /** 秒杀接口返回订单号以后，客户端轮询这里看结果；还没落库时返回“排队中” */
    @GetMapping("/order/{orderId}")
    public Result<String> status(@PathVariable long orderId) {
        VoucherOrder o = orders.find(orderId);
        if (o == null) return Result.ok("排队中");
        if (o.userId() != UserHolder.get()) throw new BizException(1005, "订单不存在");
        return Result.ok(STATUS[o.status()]);
    }

    @PostMapping("/order/{orderId}/pay")
    public Result<Void> pay(@PathVariable long orderId) {
        orders.pay(orderId, UserHolder.get());
        return Result.ok(null);
    }
}
