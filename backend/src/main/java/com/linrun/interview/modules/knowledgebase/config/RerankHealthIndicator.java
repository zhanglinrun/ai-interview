package com.linrun.interview.modules.knowledgebase.config;

import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import com.linrun.interview.modules.knowledgebase.service.RerankService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Rerank 可用性健康检查：本地 ONNX 或云端 DashScope 至少一路可用时为 UP。
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
          .withDetail("configuredProvider", queryProperties.getRerank().getProvider())
          .withDetail("hint", "本地 ONNX 模型缺失且云端 API Key 未配置，见 model/bge-reranker-model/README.md")
          .build();
    }
    return Health.up()
        .withDetail("effectiveProvider", rerankService.getEffectiveProvider())
        .withDetail("configuredProvider", queryProperties.getRerank().getProvider())
        .build();
  }
}
