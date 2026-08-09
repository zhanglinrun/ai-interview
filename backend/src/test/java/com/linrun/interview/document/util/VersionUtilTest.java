package com.linrun.interview.document.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VersionUtil 测试")
class VersionUtilTest {

  @Test
  @DisplayName("语义化版本比较")
  void compareVersionsWorks() {
    assertThat(VersionUtil.compareVersions("1.0.0", "1.0.1")).isNegative();
    assertThat(VersionUtil.compareVersions("2.0.0", "1.9.9")).isPositive();
    assertThat(VersionUtil.compareVersions("1.2.3", "1.2.3")).isZero();
  }
}
