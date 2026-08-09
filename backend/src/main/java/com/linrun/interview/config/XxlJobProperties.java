package com.linrun.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** XXL-Job 执行器配置；默认关闭，启用后由外部 Admin 调度补偿任务。 */
@Data
@ConfigurationProperties(prefix = "app.job.xxl")
public class XxlJobProperties {
  private boolean enabled = false;
  private String adminAddresses = "";
  private String appName = "ai-interview-backend";
  private String address = "";
  private String ip = "";
  private int port = 0;
  private String accessToken = "";
  private String logPath = "";
  private int logRetentionDays = 30;
}
