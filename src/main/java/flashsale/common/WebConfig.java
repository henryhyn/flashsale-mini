package flashsale.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final LoginInterceptor loginInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(LoginInterceptor loginInterceptor, RateLimitInterceptor rateLimitInterceptor) {
        this.loginInterceptor = loginInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 先限流再查登录：被限流的请求连 Redis 都不用访问
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/seckill/**");
        registry.addInterceptor(loginInterceptor).excludePathPatterns("/user/login", "/shop/**", "/lab/**", "/error");
    }
}
