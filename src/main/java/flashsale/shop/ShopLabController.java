package flashsale.shop;

import flashsale.common.BizException;
import flashsale.common.Result;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** 对照实验用的接口 (scripts/verify-m2.sh 调用)，正式部署时应该去掉 */
@RestController
@RequestMapping("/lab")
public class ShopLabController {
    private final ShopService shops;
    private final ShopMapper mapper;
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public ShopLabController(ShopService shops, ShopMapper mapper, StringRedisTemplate redis, JsonMapper json) {
        this.shops = shops;
        this.mapper = mapper;
        this.redis = redis;
        this.json = json;
    }

    /** 按不同写法读商户：db、aside、mutex (即正式接口的写法)、logical、twoLevel */
    @GetMapping("/shop/{variant}/{id}")
    public Result<Shop> read(@PathVariable String variant, @PathVariable long id) {
        return Result.ok(switch (variant) {
            case "db" -> shops.db(id);
            case "aside" -> shops.aside(id);
            case "mutex" -> shops.get(id);
            case "logical" -> shops.logical(id);
            case "twoLevel" -> shops.twoLevel(id);
            default -> throw new BizException(400, "未知的写法：" + variant);
        });
    }

    /** 按不同写法更新人均价格：afterCommit (正式写法)、deleteFirst、deleteInTx */
    @PostMapping("/shop/{id}/price")
    public Result<Void> update(@PathVariable long id, @RequestParam int avgPrice, @RequestParam String strategy) {
        switch (strategy) {
            case "afterCommit" -> shops.updateAvgPrice(id, avgPrice);
            case "deleteFirst" -> shops.updateDeleteFirst(id, avgPrice);
            case "deleteInTx" -> shops.updateDeleteInTx(id, avgPrice);
            default -> throw new BizException(400, "未知的写法：" + strategy);
        }
        return Result.ok(null);
    }

    /** 缓存里的人均价格和数据库里的是否一致 (缓存里没有这个 key 算一致) */
    @GetMapping("/shop/{id}/check")
    public Result<Map<String, Object>> check(@PathVariable long id) {
        Shop inDb = mapper.findById(id, 0);
        String v = redis.opsForValue().get(ShopService.KEY + id);
        Integer inCache = v == null || v.isEmpty() ? null : json.readValue(v, Shop.class).avgPrice();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("db", inDb.avgPrice());
        m.put("cache", inCache);
        m.put("stale", inCache != null && inCache != inDb.avgPrice());
        return Result.ok(m);
    }

    /** 让热 key 过期：删掉普通缓存，把逻辑过期的 value 写成已经过期 */
    @PostMapping("/shop/{id}/expire")
    public Result<Void> expire(@PathVariable long id) {
        redis.delete(ShopService.KEY + id);
        shops.clearLocal();
        shops.rebuildLogical(id, Duration.ofMillis(-1));
        return Result.ok(null);
    }

    /** 清空所有商户缓存和锁，数据库查询计数清零，设置模拟耗时 */
    @PostMapping("/reset")
    public Result<Void> reset(@RequestParam(defaultValue = "0") int queryDelayMs, @RequestParam(defaultValue = "0") int txWorkMs) {
        var keys = redis.keys("cache:shop:*");
        keys.addAll(redis.keys("lock:shop:*"));
        if (!keys.isEmpty()) redis.delete(keys);
        shops.clearLocal();
        shops.configure(queryDelayMs, txWorkMs);
        return Result.ok(null);
    }

    @GetMapping("/stats")
    public Result<Long> stats() {
        return Result.ok(shops.dbQueries());
    }
}
