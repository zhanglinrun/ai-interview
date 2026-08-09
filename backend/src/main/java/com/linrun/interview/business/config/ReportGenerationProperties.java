package com.linrun.interview.business.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.report.generation")
public class ReportGenerationProperties {

  /**
   * 单次报告生成的数据库租约。该值应明显大于一次 LLM 总结的正常超时，
   * 但必须有界，确保进程在 claim 后宕机时可以被其他实例接管。
   */
  private Duration claimLease = Duration.ofMinutes(10);

  /**
   * 消息已投递但尚未被消费者 claim 时的安静期，防止页面轮询和补偿任务忙重投。
   */
  private Duration recoveryGrace = Duration.ofSeconds(30);

  /** 单轮补偿扫描上限，避免低配服务器被历史任务瞬时占满。 */
  private int recoveryBatchSize = 50;
}
