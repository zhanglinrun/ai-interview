package com.linrun.interview.modules.dingtalk.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 钉钉自定义机器人 Webhook 签名校验（HMAC-SHA256）。
 *
 * <p>算法：sign = Base64(HmacSHA256(timestamp + "\n" + secret))
 */
public final class DingTalkSignatureUtils {

  private DingTalkSignatureUtils() {
  }

  public static String sign(String timestamp, String secret) {
    if (timestamp == null || secret == null || secret.isBlank()) {
      return "";
    }
    try {
      String stringToSign = timestamp + "\n" + secret;
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(signData);
    } catch (Exception e) {
      throw new IllegalStateException("钉钉签名计算失败", e);
    }
  }

  public static boolean verify(String timestamp, String providedSign, String secret) {
    if (providedSign == null || providedSign.isBlank()) {
      return false;
    }
    String expected = sign(timestamp, secret);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        providedSign.getBytes(StandardCharsets.UTF_8));
  }
}
