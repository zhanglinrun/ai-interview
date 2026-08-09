package com.linrun.interview.rag.config;

import com.linrun.interview.rag.service.LocalOnnxRerankModel;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import com.linrun.interview.rag.service.RerankService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动时预热本地 BGE Rerank 模型，避免首问冷启动延迟（对齐业界实践 {@code BgeScoringModel#init}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankWarmupConfig {

  private static final String PROVIDER_LOCAL = "local";

  private final KnowledgeBaseQueryProperties queryProperties;
  private final RerankService rerankService;

  @PostConstruct
  public void warmupLocalRerank() {
    if (!PROVIDER_LOCAL.equalsIgnoreCase(queryProperties.getRerank().getProvider())) {
      return;
    }
    try {
      boolean ready = rerankService.warmupLocalModel();
      if (ready) {
        log.info("本地 ONNX BGE-RERANKER 预热完成");
      } else {
        log.warn("本地 ONNX BGE-RERANKER 预热跳过（模型不可用，将降级云端 rerank）。"
            + "请按 backend/src/main/resources/model/bge-reranker-model/README.md 下载模型，"
            + "或设置 APP_AI_RAG_RERANK_PROVIDER=cloud");
      }
    } catch (Exception e) {
      log.warn("本地 ONNX BGE-RERANKER 预热失败，将降级云端 rerank: {}", e.getMessage(), e);
    }
  }
}
