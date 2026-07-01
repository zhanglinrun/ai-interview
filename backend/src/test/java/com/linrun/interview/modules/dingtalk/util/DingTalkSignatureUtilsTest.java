package com.linrun.interview.modules.dingtalk.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DingTalkSignatureUtils 测试")
class DingTalkSignatureUtilsTest {

  @Test
  @DisplayName("相同参数应验签通过")
  void verifyAcceptsValidSign() {
    String ts = "1710000000000";
    String secret = "SEC123";
    String sign = DingTalkSignatureUtils.sign(ts, secret);
    assertThat(DingTalkSignatureUtils.verify(ts, sign, secret)).isTrue();
  }

  @Test
  @DisplayName("错误签名应验签失败")
  void verifyRejectsInvalidSign() {
    assertThat(DingTalkSignatureUtils.verify("1", "bad", "SEC")).isFalse();
  }
}
