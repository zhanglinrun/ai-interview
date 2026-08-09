package com.linrun.interview.dingtalk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.dingtalk.config.DingTalkProperties;
import com.linrun.interview.dingtalk.model.DingTalkAccessToken;
import com.linrun.interview.infra.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** 钉钉 OAuth code 换 token，Redis 缓存避免并发刷新和重复打开放平台接口。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkAccessTokenService {

    private static final String TOKEN_URL = "https://api.dingtalk.com/v1.0/oauth2/userAccessToken";
    private final DingTalkProperties properties;
    private final RedisService redisService;
    private final RestClient restClient = RestClient.create();

    public DingTalkAccessToken exchange(String code) {
        if (!properties.isEnabled() || !StringUtils.hasText(properties.getAppKey())
            || !StringUtils.hasText(properties.getAppSecret())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "钉钉 OAuth 未配置");
        }
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "钉钉授权码不能为空");
        }
        String cacheKey = "dingtalk:oauth:token:" + sha256(code);
        try {
            String cached = redisService.get(cacheKey);
            if (StringUtils.hasText(cached)) {
                return new DingTalkAccessToken(cached, Instant.now().plusSeconds(300));
            }
            return redisService.executeWithLock(cacheKey + ":lock", 1, 10,
                java.util.concurrent.TimeUnit.SECONDS, () -> fetchAndCache(code, cacheKey));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "钉钉 OAuth 请求失败", ex);
        }
    }

    private DingTalkAccessToken fetchAndCache(String code, String cacheKey) {
        JsonNode body = restClient.post()
            .uri(TOKEN_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("clientId", properties.getAppKey(),
                "clientSecret", properties.getAppSecret(), "code", code))
            .retrieve()
            .body(JsonNode.class);
        String token = body == null || body.get("accessToken") == null
            ? "" : body.get("accessToken").asText("");
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "钉钉 OAuth 未返回 accessToken");
        }
        long expiresIn = body.get("expireIn") == null ? 3600 : body.get("expireIn").asLong(3600);
        Duration ttl = Duration.ofSeconds(Math.max(30, Math.min(expiresIn - 30, 86_400)));
        redisService.set(cacheKey, token, ttl);
        return new DingTalkAccessToken(token, Instant.now().plus(ttl));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "钉钉 OAuth 缓存键生成失败", ex);
        }
    }
}
