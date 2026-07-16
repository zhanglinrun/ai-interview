package com.linrun.interview.modules.jobinterview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.job-interview")
public class JobInterviewProperties {
  private String promptVersion = "job-interview-v2";
  private int followUpLimitPerMainQuestion = 1;
  private int reflectionLimitPerSession = 1;
  private int naturalCloseMinutes = 5;
  private int reconnectEventLimit = 100;
  /** 外部调用期间的指令租约；超时后由请求侧或生命周期补偿安全回收。 */
  private int commandLeaseSeconds = 300;
  private int idlePauseMinutes = 30;
  private int resumeHours = 24;
  private int maxAnswerChars = 12000;
  private int maxCodeChars = 50000;
}
