package flashsale.shop;

import flashsale.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shop")
public class ShopController {
    private final ShopService shops;

    public ShopController(ShopService shops) { this.shops = shops; }

    @GetMapping("/{id}")
    public Result<Shop> get(@PathVariable long id) {
        return Result.ok(shops.get(id));
    }
}
