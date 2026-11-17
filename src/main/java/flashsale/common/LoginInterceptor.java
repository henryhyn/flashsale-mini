package flashsale.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 从 Authorization 头里取 token，到 Redis 查出用户 id，放进 UserHolder；顺便刷新 token 的过期时间 */
@Component
public class LoginInterceptor implements HandlerInterceptor {
    public static final String TOKEN_KEY = "login:token:";
    public static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;

    public LoginInterceptor(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        String token = req.getHeader("Authorization");
        if (token == null || token.isBlank()) {
            resp.setStatus(401);
            return false;
        }
        String userId = redis.opsForValue().getAndExpire(TOKEN_KEY + token, TOKEN_TTL);
        if (userId == null) {
            resp.setStatus(401);
            return false;
        }
        UserHolder.set(Long.valueOf(userId));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        UserHolder.remove();
    }
}
