package com.linrun.interview.common.security;

import com.linrun.interview.common.config.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JWT 工具测试")
class JwtUtilTest {

  @Test
  @DisplayName("access token 与 refresh token 不能混用")
  void tokenTypeCannotBeMixed() {
    JwtUtil jwtUtil = new JwtUtil(buildSecurityProperties());

    String accessToken = jwtUtil.generateAccessToken(42L, "alice");
    String refreshToken = jwtUtil.generateRefreshToken(42L);

    assertThat(jwtUtil.extractAccessUserId(accessToken)).isEqualTo(42L);
    assertThat(jwtUtil.extractRefreshUserId(refreshToken)).isEqualTo(42L);
    assertThat(jwtUtil.extractAccessUserId(refreshToken)).isNull();
    assertThat(jwtUtil.extractRefreshUserId(accessToken)).isNull();
    assertThat(jwtUtil.validateToken(accessToken)).isTrue();
    assertThat(jwtUtil.validateToken(refreshToken)).isFalse();
  }

  private SecurityProperties buildSecurityProperties() {
    SecurityProperties properties = new SecurityProperties();
    properties.getJwt().setSecret("test-jwt-secret-at-least-32-bytes-long");
    return properties;
  }
}
