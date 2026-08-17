package com.linrun.interview.rag.config;

import com.linrun.interview.rag.service.RerankService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 本地 BGE rerank 可用性健康检查。
 */
@Component
@RequiredArgsConstructor
public class RerankHealthIndicator implements HealthIndicator {

  private final KnowledgeBaseQueryProperties queryProperties;
  private final RerankService rerankService;

  @Override
  public Health health() {
    if (!queryProperties.getRerank().isEnabled()) {
      return Health.up().withDetail("status", "disabled").build();
    }
    if (!rerankService.isEnabled()) {
      return Health.down()
          .withDetail("status", "unavailable")
          .withDetail("provider", "local")
          .withDetail("hint", "本地 ONNX 模型缺失，见 model/bge-reranker-model/README.md")
          .build();
    }
    return Health.up()
        .withDetail("provider", rerankService.getEffectiveProvider())
        .build();
  }
}
