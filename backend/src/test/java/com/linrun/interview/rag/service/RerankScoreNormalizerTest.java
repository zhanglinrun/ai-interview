package com.linrun.interview.rag.service;

import dev.langchain4j.rag.content.ContentMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("BGE rerank 分数归一化")
class RerankScoreNormalizerTest {

  @Test
  @DisplayName("已在 0~1 的分数保持原值")
  void keepsUnitIntervalScores() {
    assertThat(RerankScoreNormalizer.looksLikeLogit(List.of(0.9, 0.2, 0.6))).isFalse();
    assertThat(RerankScoreNormalizer.toUnitInterval(List.of(0.9, 0.2)))
        .containsExactly(0.9, 0.2);
  }

  @Test
  @DisplayName("出现大于 1 或小于 0 时按 logit 做 sigmoid")
  void mapsLogitsThroughSigmoid() {
    assertThat(RerankScoreNormalizer.looksLikeLogit(List.of(5.07, 1.77))).isTrue();
    List<Double> normalized = RerankScoreNormalizer.toUnitInterval(List.of(5.07, 4.15, 1.77));

    assertThat(normalized.get(0)).isCloseTo(RerankScoreNormalizer.sigmoid(5.07), within(1e-6));
    assertThat(normalized.get(2)).isCloseTo(RerankScoreNormalizer.sigmoid(1.77), within(1e-6));
    assertThat(normalized.get(0)).isGreaterThan(0.99);
    assertThat(normalized.get(2)).isLessThan(0.88);
  }

  @Test
  @DisplayName("展示分优先 rerank，不把检索 BM25 当分母做 sigmoid")
  void prefersRerankScoreForDisplay() {
    Double display = RerankScoreNormalizer.pickDisplayScore(Map.of(
        ContentMetadata.SCORE, 5.07d,
        ContentMetadata.RERANKED_SCORE, 0.9845d));

    assertThat(display).isEqualTo(0.9845d);
  }

  @Test
  @DisplayName("只有检索分时原样展示，即使大于 1")
  void keepsRetrievalScoreWhenRerankMissing() {
    Double display = RerankScoreNormalizer.pickDisplayScore(Map.of(
        ContentMetadata.SCORE, 5.07d));

    assertThat(display).isEqualTo(5.07d);
  }
}
