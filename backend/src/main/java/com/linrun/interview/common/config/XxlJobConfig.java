package com.linrun.interview.common.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "xxl.job", name = "admin.addresses")
public class XxlJobConfig {

  @Value("${xxl.job.admin.addresses}")
  private String adminAddresses;

  @Value("${xxl.job.executor.appname}")
  private String appname;

  @Value("${xxl.job.executor.port:9999}")
  private int port;

  @Value("${xxl.job.accessToken:}")
  private String accessToken;

  @Bean
  public XxlJobSpringExecutor xxlJobExecutor() {
    log.info("Initializing XXL-Job executor: appname={}", appname);
    XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
    executor.setAdminAddresses(adminAddresses);
    executor.setAppname(appname);
    executor.setPort(port);
    executor.setAccessToken(accessToken);
    return executor;
  }
}
