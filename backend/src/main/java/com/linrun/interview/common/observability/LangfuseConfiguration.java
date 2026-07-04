package com.linrun.interview.common.observability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 可观测配置（P5）：启用 {@link LangfuseProperties}，注册 {@link TraceIdFilter}。
 *
 * <p>traceId 过滤器最高优先级运行，保证后续所有日志与观测都带上同一个 traceId。
 */
@Configuration
@EnableConfigurationProperties(LangfuseProperties.class)
public class LangfuseConfiguration {

  @Bean
  public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
    FilterRegistrationBean<TraceIdFilter> registration =
        new FilterRegistrationBean<>(new TraceIdFilter());
    registration.addUrlPatterns("/api/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
    registration.setName("traceIdFilter");
    return registration;
  }
}
