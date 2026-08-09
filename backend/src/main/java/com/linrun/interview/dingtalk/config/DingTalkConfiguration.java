package com.linrun.interview.dingtalk.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册钉钉配置，未启用时仍允许开发环境启动。 */
@Configuration
@EnableConfigurationProperties(DingTalkProperties.class)
public class DingTalkConfiguration {
}
