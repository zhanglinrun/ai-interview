package com.linrun.interview.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可选 XXL-Job 执行器。
 *
 * <p>本地仍可使用现有 {@code @Scheduled} 兜底；生产打开开关后，同一个补偿处理方法同时
 * 暴露为 XXL-Job Handler，支持统一调度、重试和执行日志。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(XxlJobProperties.class)
@ConditionalOnProperty(prefix = "app.job.xxl", name = "enabled", havingValue = "true")
public class XxlJobConfiguration {

  @Bean
  public XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties properties) {
    if (properties.getAdminAddresses() == null || properties.getAdminAddresses().isBlank()) {
      throw new IllegalStateException("XXL-Job 已启用，但 app.job.xxl.admin-addresses 为空");
    }
    log.info("初始化 XXL-Job 执行器: appName={}, admin={}",
        properties.getAppName(), properties.getAdminAddresses());
    XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
    executor.setAdminAddresses(properties.getAdminAddresses());
    executor.setAppname(properties.getAppName());
    executor.setAddress(properties.getAddress());
    executor.setIp(properties.getIp());
    executor.setPort(properties.getPort());
    executor.setAccessToken(properties.getAccessToken());
    executor.setLogPath(properties.getLogPath());
    executor.setLogRetentionDays(properties.getLogRetentionDays());
    return executor;
  }
}
