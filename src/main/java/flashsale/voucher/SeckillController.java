package flashsale.voucher;

import flashsale.common.BizException;
import flashsale.common.Result;
import flashsale.common.UserHolder;
import flashsale.order.AsyncSeckillService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeckillController {
    private final SeckillService seckill;
    private final LuaSeckillService lua;
    private final LockSeckillService locks;
    private final AsyncSeckillService async;

    public SeckillController(SeckillService seckill, LuaSeckillService lua, LockSeckillService locks, AsyncSeckillService async) {
        this.seckill = seckill;
        this.lua = lua;
        this.locks = locks;
        this.async = async;
    }

    /** 正式接口：Redis + Lua 判断资格，发消息异步落库 (m4)。返回订单号，客户端用 GET /order/{id} 查结果 */
    @PostMapping("/seckill/{voucherId}")
    public Result<String> seckill(@PathVariable long voucherId) {
        return Result.ok(String.valueOf(async.seckill(UserHolder.get(), voucherId)));
    }

    /** m3 的写法：Redis + Lua 判断资格，再同步落库 */
    @PostMapping("/seckill/redis/{voucherId}")
    public Result<String> redis(@PathVariable long voucherId) {
        return Result.ok(String.valueOf(lua.seckill(UserHolder.get(), voucherId)));
    }

    /** m1 的正确写法：直接在数据库里条件更新 + 唯一索引 */
    @PostMapping("/seckill/sync/{voucherId}")
    public Result<String> sync(@PathVariable long voucherId) {
        return Result.ok(String.valueOf(seckill.safe(UserHolder.get(), voucherId)));
    }

    /** m1 的错误写法，只用来做对比实验 */
    @PostMapping("/seckill/naive/{voucherId}")
    public Result<String> naive(@PathVariable long voucherId) {
        return Result.ok(String.valueOf(seckill.naive(UserHolder.get(), voucherId)));
    }

    /** m3 的对照实验：订单都写进没有唯一索引的演示表，一人一单只靠各自的机制 */
    @PostMapping("/seckill/lab/{variant}/{voucherId}")
    public Result<String> lab(@PathVariable String variant, @PathVariable long voucherId) {
        long userId = UserHolder.get();
        return Result.ok(String.valueOf(switch (variant) {
            case "jvmLock" -> locks.jvmLock(userId, voucherId);
            case "redissonLock" -> locks.redissonLock(userId, voucherId);
            case "lua" -> lua.seckill(userId, voucherId, true);
            default -> throw new BizException(400, "未知的写法：" + variant);
        }));
    }
}
