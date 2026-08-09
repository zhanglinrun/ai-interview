package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.linrun.interview.business.constant.CapabilityState;
import com.linrun.interview.business.service.CapabilityProfileAggregator.Observation;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("最近三次能力画像确定性投影")
class CapabilityProfileAggregatorTest {

  private final CapabilityProfileAggregator aggregator = new CapabilityProfileAggregator();

  @Nested
  @DisplayName("晋级规则")
  class Promotion {

    @Test
    @DisplayName("两次无提示高质量有效证据可投影为稳定")
    void shouldProjectStableFromEligibleEvidence() {
      var result = aggregator.project(List.of(
          observation("new", 80, 80, true, true, false, false, 0, 0),
          observation("old", 75, 75, true, true, false, false, 0, 1)));

      assertThat(result.state()).isEqualTo(CapabilityState.STABLE);
      assertThat(result.recent()).extracting(Observation::evidenceRecordId)
          .containsExactly("new", "old");
    }

    @Test
    @DisplayName("使用提示或查看答案的训练不能把能力提升为稳定")
    void shouldNotPromoteHintedPractice() {
      var result = aggregator.project(List.of(
          observation("hint", 95, 95, true, false, true, false, 0, 0),
          observation("answer", 95, 95, true, false, false, true, 0, 1),
          observation("redo", 95, 95, true, false, false, false, 1, 2)));

      assertThat(result.state()).isEqualTo(CapabilityState.UNVERIFIED);
    }

    @Test
    @DisplayName("只使用最近三次而忽略更老的历史高分")
    void shouldUseOnlyLatestThree() {
      var result = aggregator.project(List.of(
          observation("n1", 40, 40, true, true, false, false, 0, 0),
          observation("n2", 45, 45, true, true, false, false, 0, 1),
          observation("n3", 50, 50, true, true, false, false, 0, 2),
          observation("old", 100, 100, true, true, false, false, 0, 3)));

      assertThat(result.state()).isEqualTo(CapabilityState.WEAK);
      assertThat(result.recent()).extracting(Observation::evidenceRecordId)
          .containsExactly("n1", "n2", "n3");
    }
  }

  @Nested
  @DisplayName("冲突与客观结果")
  class ConflictAndJudge {

    @Test
    @DisplayName("最近有效证据跨度过大时要求复核")
    void shouldMarkConflictForLargeSpread() {
      var result = aggregator.project(List.of(
          observation("high", 95, 90, true, true, false, false, 0, 0),
          observation("low", 40, 45, true, true, false, false, 0, 1)));

      assertThat(result.state()).isEqualTo(CapabilityState.REVIEW);
      assertThat(result.reviewRequired()).isTrue();
    }

    @Test
    @DisplayName("客观执行失败不可被语言评价高分覆盖")
    void shouldCapFailedObjectiveExecution() {
      var result = aggregator.project(List.of(
          observation("judge-failed", 100, 100, false,
              true, false, false, 0, 0),
          observation("second", 100, 100, false,
              true, false, false, 0, 1)));

      assertThat(result.state()).isEqualTo(CapabilityState.WEAK);
    }

    @Test
    @DisplayName("低置信度待评估项不污染画像")
    void shouldIgnoreLowConfidenceObservation() {
      Observation pending = new Observation(
          "pending", 100, 100, true, 0.2d,
          true, false, false, 0, LocalDateTime.now());

      assertThat(aggregator.project(List.of(pending)).state())
          .isEqualTo(CapabilityState.UNVERIFIED);
    }
  }

  private Observation observation(
      String id,
      int technical,
      int completeness,
      Boolean objectivePassed,
      boolean eligible,
      boolean hint,
      boolean viewed,
      int redo,
      int daysAgo
  ) {
    return new Observation(
        id, technical, completeness, objectivePassed, 0.90d,
        eligible, hint, viewed, redo, LocalDateTime.now().minusDays(daysAgo));
  }
}
