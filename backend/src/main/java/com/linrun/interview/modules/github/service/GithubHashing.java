package com.linrun.interview.modules.github.service;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class GithubHashing {

  private GithubHashing() {
  }

  public static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte item : digest) {
        hex.append(String.format("%02x", item));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算 GitHub 证据哈希失败", e);
    }
  }
}
