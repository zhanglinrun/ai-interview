package com.linrun.interview.document.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 知识库向量化任务的租约、退避和有界重试配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.knowledgebase.vectorization")
public class VectorizationTaskProperties {

  private int maxAttempts = 5;
  private Duration claimLease = Duration.ofMinutes(30);
  private Duration baseBackoff = Duration.ofMinutes(1);
  private Duration maxBackoff = Duration.ofHours(1);
  private int recoveryBatchSize = 50;
}
