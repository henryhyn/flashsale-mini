package flashsale.voucher;

import flashsale.common.RateLimitInterceptor;
import flashsale.common.Result;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 秒杀的运营和实验接口 (scripts/verify-m3.sh 调用)。真实项目里预热由运营后台触发，要有权限校验 */
@RestController
@RequestMapping("/lab/seckill")
public class SeckillLabController {
    private final LuaSeckillService lua;
    private final RateLimitInterceptor rateLimit;

    public SeckillLabController(LuaSeckillService lua, RateLimitInterceptor rateLimit) {
        this.lua = lua;
        this.rateLimit = rateLimit;
    }

    /** 预热：把秒杀券的库存加载进 Redis */
    @PostMapping("/{voucherId}/preheat")
    public Result<Void> preheat(@PathVariable long voucherId, @RequestParam int stock) {
        lua.preheat(voucherId, stock);
        return Result.ok(null);
    }

    /** 设置秒杀接口的限流：每秒放多少个令牌、桶的容量；permitsPerSecond 为 0 表示不限流 */
    @PostMapping("/rateLimit")
    public Result<Void> rateLimit(@RequestParam double permitsPerSecond, @RequestParam(defaultValue = "0") double capacity) {
        rateLimit.configure(permitsPerSecond, capacity);
        return Result.ok(null);
    }
}
