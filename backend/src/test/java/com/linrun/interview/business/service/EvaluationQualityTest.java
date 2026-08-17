package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("评估质量判定")
class EvaluationQualityTest {

  @Test
  @DisplayName("兜底文案视为降级报告")
  void detectsDegradedFeedback() {
    assertThat(EvaluationQuality.isDegradedFeedback("该题未成功生成评估结果，系统按 0 分处理。"))
        .isTrue();
    assertThat(EvaluationQuality.isDegradedFeedback("批次评估失败，已按 0 分兜底。")).isTrue();
    assertThat(EvaluationQuality.isDegradedFeedback("能说明失败窗口与工程取舍。")).isFalse();
  }

  @Test
  @DisplayName("全未答的 0 分是有效报告")
  void unansweredZeroIsValid() {
    assertThat(EvaluationQuality.isValidStoredReport("作答率 0/8。", false, List.of())).isTrue();
  }

  @Test
  @DisplayName("有作答但全部是兜底反馈则不是有效报告")
  void answeredFallbackIsInvalid() {
    assertThat(EvaluationQuality.isValidStoredReport(
        "8道题均按0分兜底",
        true,
        List.of("该题未成功生成评估结果，系统按 0 分处理。"))).isFalse();
  }

  @Test
  @DisplayName("补偿次数从 evaluateError 解析")
  void parsesCompensationAttempts() {
    assertThat(EvaluationQuality.compensationAttempts(null)).isZero();
    assertThat(EvaluationQuality.compensationAttempts("[compensate:2] 上次失败")).isEqualTo(2);
    assertThat(EvaluationQuality.canCompensate(2)).isTrue();
    assertThat(EvaluationQuality.canCompensate(3)).isFalse();
  }
}
