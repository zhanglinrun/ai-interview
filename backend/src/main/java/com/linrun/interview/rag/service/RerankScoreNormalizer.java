package com.linrun.interview.rag.service;

import dev.langchain4j.rag.content.ContentMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BGE-RERANKER 分数归一化。
 *
 * <p>本地 ONNX {@code bge-reranker-v2-m3} 输出的是未做 sigmoid 的 logit（常见 +1～+8），
 * 配置里的 {@code min-score} 按 0～1 理解。先识别 logit，再压到单位区间，阈值才有意义。
 */
public final class RerankScoreNormalizer {

  private static final double UNIT_MAX = 1.0 + 1e-6;

  private RerankScoreNormalizer() {
  }

  public static boolean looksLikeLogit(double score) {
    return Double.isFinite(score) && (score < 0.0 || score > UNIT_MAX);
  }

  public static boolean looksLikeLogit(List<Double> scores) {
    if (scores == null || scores.isEmpty()) {
      return false;
    }
    for (Double score : scores) {
      if (score != null && looksLikeLogit(score)) {
        return true;
      }
    }
    return false;
  }

  public static double sigmoid(double logit) {
    if (logit >= 20.0) {
      return 1.0;
    }
    if (logit <= -20.0) {
      return 0.0;
    }
    return 1.0 / (1.0 + Math.exp(-logit));
  }

  public static double toUnitInterval(double score) {
    if (!Double.isFinite(score)) {
      return score;
    }
    return looksLikeLogit(score) ? sigmoid(score) : score;
  }

  public static List<Double> toUnitInterval(List<Double> scores) {
    if (scores == null || scores.isEmpty()) {
      return scores == null ? List.of() : scores;
    }
    if (!looksLikeLogit(scores)) {
      return scores;
    }
    List<Double> normalized = new ArrayList<>(scores.size());
    for (Double score : scores) {
      normalized.add(score == null ? null : toUnitInterval(score));
    }
    return normalized;
  }

  /**
   * 卡片展示分：优先 rerank（必要时再压到 0～1），否则退回检索 {@code SCORE}（BM25 / 向量分，不套 sigmoid）。
   */
  public static Double pickDisplayScore(Map<ContentMetadata, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return null;
    }
    Double reranked = asFinite(metadata.get(ContentMetadata.RERANKED_SCORE));
    if (reranked != null) {
      return round4(toUnitInterval(reranked));
    }
    Double retrieval = asFinite(metadata.get(ContentMetadata.SCORE));
    return retrieval == null ? null : round4(retrieval);
  }

  private static Double asFinite(Object value) {
    if (value instanceof Number number) {
      double score = number.doubleValue();
      if (Double.isFinite(score)) {
        return score;
      }
    }
    return null;
  }

  private static double round4(double value) {
    return Math.round(value * 10000.0) / 10000.0;
  }
}
