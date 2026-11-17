package flashsale.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 令牌桶限流 (单机)：令牌按固定速率放进桶里，桶满了就不再放；每个请求取一个令牌，取不到就直接拒绝。
 * 桶的容量允许一定的突发，长期来看通过的速率不超过放令牌的速率。速率为 0 表示不限流 (默认)。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private double permitsPerSecond, capacity, tokens;
    private long lastNanos = System.nanoTime();

    /** 修改速率和桶容量，桶重新装满 */
    public synchronized void configure(double permitsPerSecond, double capacity) {
        this.permitsPerSecond = permitsPerSecond;
        this.capacity = capacity;
        this.tokens = capacity;
        this.lastNanos = System.nanoTime();
    }

    synchronized boolean tryAcquire() {
        if (permitsPerSecond <= 0) return true;
        long now = System.nanoTime();
        tokens = Math.min(capacity, tokens + (now - lastNanos) / 1e9 * permitsPerSecond);   // 补上这段时间该放的令牌
        lastNanos = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws IOException {
        if (tryAcquire()) return true;
        resp.setStatus(429);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"code\":429,\"msg\":\"请求太多，请稍后再试\",\"data\":null}");
        return false;
    }
}
