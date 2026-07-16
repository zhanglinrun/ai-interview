package com.linrun.interview.common.config;

import com.linrun.interview.common.security.JwtInterceptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 JWT 拦截器，排除认证接口和公开端点。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final AsyncTaskExecutor questionExecutor;

    public WebMvcConfig(
            JwtInterceptor jwtInterceptor,
            @Qualifier("questionExecutor") AsyncTaskExecutor questionExecutor) {
        this.jwtInterceptor = jwtInterceptor;
        this.questionExecutor = questionExecutor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(questionExecutor);
    }

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
