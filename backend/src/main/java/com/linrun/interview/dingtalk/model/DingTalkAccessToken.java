package com.linrun.interview.dingtalk.model;

import java.time.Instant;

/** 钉钉 OAuth 访问令牌的脱敏领域结果。 */
public record DingTalkAccessToken(String accessToken, Instant expiresAt) {
}
