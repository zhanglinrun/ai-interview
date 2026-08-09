package com.linrun.interview.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LLM 用量显式上下文")
class LlmUsageContextTest {

  @Test
  @DisplayName("作用域关闭后恢复外层上下文且重试序号递增")
  void shouldRestoreNestedContext() {
    try (var outer = LlmUsageContext.open(1L, "session-a", null, "OUTER")) {
      assertThat(LlmUsageContext.current().nextRetryCount()).isZero();
      assertThat(LlmUsageContext.current().nextRetryCount()).isEqualTo(1);

      try (var inner = LlmUsageContext.open(2L, "session-b", "report-b", "INNER")) {
        assertThat(LlmUsageContext.current().userId()).isEqualTo(2L);
        assertThat(LlmUsageContext.current().nextRetryCount()).isZero();
      }

      assertThat(LlmUsageContext.current().userId()).isEqualTo(1L);
      assertThat(LlmUsageContext.current().nextRetryCount()).isEqualTo(2);
    }
    assertThat(LlmUsageContext.current()).isNull();
  }

  @Test
  @DisplayName("兜底上下文不会覆盖已有业务上下文")
  void shouldKeepExistingContextWhenOpeningFallback() {
    try (var outer = LlmUsageContext.open(3L, "session", "report", "REPORT")) {
      LlmUsageContext.Context expected = LlmUsageContext.current();
      try (var ignored = LlmUsageContext.openIfAbsent(9L, "BYOK_CHAT")) {
        assertThat(LlmUsageContext.current()).isSameAs(expected);
      }
      assertThat(LlmUsageContext.current()).isSameAs(expected);
    }
    assertThat(LlmUsageContext.current()).isNull();
  }
}
