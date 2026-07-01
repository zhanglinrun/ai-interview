package com.linrun.interview.modules.dingtalk.service;

import com.aliyun.dingtalkoauth2_1_0.Client;
import com.aliyun.dingtalkoauth2_1_0.models.GetAccessTokenRequest;
import com.aliyun.dingtalkoauth2_1_0.models.GetAccessTokenResponse;
import com.aliyun.dingtalkoauth2_1_0.models.GetAccessTokenResponseBody;
import com.linrun.interview.modules.dingtalk.config.DingTalkProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 钉钉企业内部应用 AccessToken 缓存（对齐 know-engine AccessTokenService）。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.dingtalk", name = "enabled", havingValue = "true")
public class DingTalkAccessTokenService {

  private static final long REFRESH_SKEW_SECONDS = 120;

  private final DingTalkProperties properties;
  private final Client oauthClient;
  private final AtomicReference<CachedToken> cache = new AtomicReference<>();

  public DingTalkAccessTokenService(DingTalkProperties properties) throws Exception {
    this.properties = properties;
    com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config();
    config.protocol = "https";
    config.regionId = "central";
    this.oauthClient = new Client(config);
  }

  public String getAccessToken() {
    if (properties.getAppKey() == null || properties.getAppKey().isBlank()
        || properties.getAppSecret() == null || properties.getAppSecret().isBlank()) {
      throw new IllegalStateException("钉钉 AppKey/AppSecret 未配置");
    }
    CachedToken current = cache.get();
    if (current != null && current.expiresAtEpochSec() > Instant.now().getEpochSecond()) {
      return current.token();
    }
    synchronized (this) {
      current = cache.get();
      if (current != null && current.expiresAtEpochSec() > Instant.now().getEpochSecond()) {
        return current.token();
      }
      try {
        GetAccessTokenRequest request = new GetAccessTokenRequest()
            .setAppKey(properties.getAppKey())
            .setAppSecret(properties.getAppSecret());
        GetAccessTokenResponse response = oauthClient.getAccessToken(request);
        GetAccessTokenResponseBody body = response.getBody();
        if (body == null || body.getAccessToken() == null || body.getAccessToken().isBlank()) {
          throw new IllegalStateException("钉钉 accessToken 响应无效");
        }
        long expireIn = body.getExpireIn() != null ? body.getExpireIn() : 7200L;
        long expiresAt = Instant.now().getEpochSecond() + Math.max(60, expireIn - REFRESH_SKEW_SECONDS);
        cache.set(new CachedToken(body.getAccessToken(), expiresAt));
        log.info("[DingTalkAccessTokenService] accessToken 已刷新, expireIn={}s", expireIn);
        return body.getAccessToken();
      } catch (Exception e) {
        log.error("[DingTalkAccessTokenService] 获取 accessToken 失败", e);
        throw new IllegalStateException("获取钉钉 accessToken 失败: " + e.getMessage(), e);
      }
    }
  }

  private record CachedToken(String token, long expiresAtEpochSec) {
  }
}
