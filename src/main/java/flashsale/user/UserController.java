package flashsale.user;

import flashsale.common.BizException;
import flashsale.common.LoginInterceptor;
import flashsale.common.Result;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {
    private final UserMapper userMapper;
    private final StringRedisTemplate redis;

    public UserController(UserMapper userMapper, StringRedisTemplate redis) {
        this.userMapper = userMapper;
        this.redis = redis;
    }

    /**
     * 登录：为了把篇幅留给秒杀，这里省掉了短信验证码，按手机号直接登录。
     * 登录态是一个随机 token，存在 Redis 里 (多个实例共享)，而不是存在某一台机器的 HttpSession 里。
     */
    @PostMapping("/user/login")
    public Result<String> login(@RequestParam String phone) {
        Long userId = userMapper.findIdByPhone(phone);
        if (userId == null) throw new BizException(404, "用户不存在");
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(LoginInterceptor.TOKEN_KEY + token, userId.toString(), LoginInterceptor.TOKEN_TTL);
        return Result.ok(token);
    }
}
