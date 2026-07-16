package com.linrun.interview.modules.algorithm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.modules.algorithm.client.Judge0JudgeClient;
import com.linrun.interview.modules.algorithm.client.JudgeClient;
import com.linrun.interview.modules.algorithm.client.UnavailableJudgeClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 根据显式开关装配外部 Judge0 或纯本地降级客户端。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Judge0Properties.class)
public class JudgeClientConfiguration {

  @Bean
  @ConditionalOnProperty(
      prefix = "app.algorithm.judge0",
      name = "enabled",
      havingValue = "true")
  public JudgeClient judge0JudgeClient(
      Judge0Properties properties,
      ObjectMapper objectMapper
  ) {
    return new Judge0JudgeClient(properties, objectMapper);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "app.algorithm.judge0",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  public JudgeClient unavailableJudgeClient() {
    return new UnavailableJudgeClient();
  }
}
