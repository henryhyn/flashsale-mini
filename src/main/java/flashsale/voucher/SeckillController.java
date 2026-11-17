package flashsale.voucher;

import flashsale.common.Result;
import flashsale.common.UserHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeckillController {
    private final SeckillService seckill;

    public SeckillController(SeckillService seckill) { this.seckill = seckill; }

    /** 正确写法 */
    @PostMapping("/seckill/{voucherId}")
    public Result<String> seckill(@PathVariable long voucherId) {
        return Result.ok(String.valueOf(seckill.safe(UserHolder.get(), voucherId)));
    }

    /** 错误写法，只用来做对比实验 */
    @PostMapping("/seckill/naive/{voucherId}")
    public Result<String> naive(@PathVariable long voucherId) {
        return Result.ok(String.valueOf(seckill.naive(UserHolder.get(), voucherId)));
    }
}
