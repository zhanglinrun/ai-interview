package com.linrun.interview.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：只承载异步执行器等 Web 基础设施。
 *
 * <p>认证拦截由 {@code auth.config.SaTokenConfig} 统一负责，避免 Web 配置和
 * 业务代码再次出现第二套 token 解析逻辑。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AsyncTaskExecutor questionExecutor;

    @Autowired
    public WebMvcConfig(
            @Qualifier("questionExecutor") AsyncTaskExecutor questionExecutor) {
        this.questionExecutor = questionExecutor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(questionExecutor);
    }

}
