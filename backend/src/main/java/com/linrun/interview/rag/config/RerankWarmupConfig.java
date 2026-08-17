package com.linrun.interview.rag.config;

import com.linrun.interview.rag.service.RerankService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动时预热本地 BGE Rerank 模型，避免首问冷启动延迟。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankWarmupConfig {

  private final KnowledgeBaseQueryProperties queryProperties;
  private final RerankService rerankService;

  @PostConstruct
  public void warmupLocalRerank() {
    if (!queryProperties.getRerank().isEnabled()) {
      return;
    }
    try {
      if (rerankService.warmupLocalModel()) {
        log.info("本地 ONNX BGE-RERANKER 预热完成");
      } else {
        log.warn("""
            本地 ONNX BGE-RERANKER 预热跳过（模型不可用，将退回 RRF 融合排序）。
            请按 backend/src/main/resources/model/bge-reranker-model/README.md 下载模型。
            """);
      }
    } catch (Exception e) {
      log.warn("本地 ONNX BGE-RERANKER 预热失败: {}", e.getMessage(), e);
    }
  }
}
