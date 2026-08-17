package com.linrun.interview.rag.service;

import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地 BGE-RERANKER 重排服务。
 *
 * <p>仅使用进程内 ONNX {@link LocalOnnxRerankModel}，不走云端 rerank。
 * 模型不可用时 {@link #isEnabled()} 为 false，上层退回 RRF 融合顺序。</p>
 */
@Slf4j
@Service
public class RerankService implements ScoringModel {

  private static final String PROVIDER_LOCAL = "local";

  private final KnowledgeBaseQueryProperties.Rerank rerankProps;
  private final LocalOnnxRerankModel localRerankModel;

  public RerankService(KnowledgeBaseQueryProperties queryProperties) {
    this.rerankProps = queryProperties.getRerank();
    this.localRerankModel = new LocalOnnxRerankModel(rerankProps.getLocal());
    log.info("[RerankService] 本地 ONNX BGE rerank 已配置，模型可用={}", localRerankModel.isAvailable());
  }

  public boolean isEnabled() {
    return rerankProps.isEnabled() && localRerankModel.isAvailable();
  }

  /** 固定为 local，保留给健康检查与评测报告。 */
  public String getEffectiveProvider() {
    return PROVIDER_LOCAL;
  }

  public boolean warmupLocalModel() {
    return localRerankModel.isAvailable();
  }

  @Override
  public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
    if (segments == null || segments.isEmpty()) {
      return Response.from(List.of());
    }
    if (query == null || query.isBlank() || !localRerankModel.isAvailable()) {
      return Response.from(zeroScores(segments.size()));
    }
    try {
      return localRerankModel.scoreAll(segments, query);
    } catch (Exception e) {
      log.warn("[RerankService] 本地 BGE rerank 失败，保留 RRF 顺序: {}", e.getMessage(), e);
      return Response.from(zeroScores(segments.size()));
    }
  }

  private List<Double> zeroScores(int size) {
    List<Double> zeros = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      zeros.add(0.0);
    }
    return zeros;
  }
}
