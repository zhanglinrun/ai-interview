package com.linrun.interview.common.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers lightweight request tracing independently from any external tracing vendor. */
@Configuration
public class ObservabilityConfiguration {

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
