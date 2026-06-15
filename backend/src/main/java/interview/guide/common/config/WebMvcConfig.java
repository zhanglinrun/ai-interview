package interview.guide.common.config;

import interview.guide.common.security.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 JWT 拦截器，排除认证接口和公开端点。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                "/api/auth/**",           // 认证接口（注册/登录/刷新）
                "/api/llm-provider/status", // 公开：LLM Provider 状态查询
                "/actuator/**",           // Actuator 监控端点
                "/swagger-ui/**",         // Swagger UI
                "/v3/api-docs/**",        // OpenAPI 文档
                "/error"                  // 错误页
            );
    }
}
