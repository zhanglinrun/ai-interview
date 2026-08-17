package com.linrun.interview.business.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Prompt 头尾截断")
class PromptTextUtilTest {

  @Test
  @DisplayName("短文本不截断")
  void keepsShortText() {
    assertThat(PromptTextUtil.headTailTruncate("hello", 100)).isEqualTo("hello");
  }

  @Test
  @DisplayName("长文本保留头尾并插入省略号")
  void keepsHeadAndTail() {
    String text = "A".repeat(40) + "MIDDLE" + "Z".repeat(40);
    String truncated = PromptTextUtil.headTailTruncate(text, 50);
    assertThat(truncated).contains("…");
    assertThat(truncated).startsWith("A");
    assertThat(truncated).endsWith("Z");
    assertThat(truncated.length()).isLessThanOrEqualTo(50);
    assertThat(truncated).doesNotContain("MIDDLE");
  }

  @Test
  @DisplayName("null 返回空串")
  void nullSafe() {
    assertThat(PromptTextUtil.headTailTruncate(null, 10)).isEmpty();
  }
}
