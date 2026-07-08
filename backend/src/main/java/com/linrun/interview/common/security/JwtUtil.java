package com.linrun.interview.common.security;

import com.linrun.interview.common.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 工具类：生成、解析、验证 token。
 * 使用 HS256 签名，密钥从配置读取（至少 32 字节）。
 */
@Slf4j
@Component
public class JwtUtil {

    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String REFRESH_TOKEN_TYPE = "refresh";

    /** application.yml 里的占位默认值；生产必须覆盖，检测到即拒绝启动，避免公开密钥可伪造任意 token。 */
    private static final String INSECURE_DEFAULT_SECRET =
        "change-this-to-a-strong-random-secret-at-least-32-bytes-long";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey secretKey;
    private final long accessTokenValidityMs;
    private final long refreshTokenValidityMs;

    public JwtUtil(SecurityProperties securityProperties) {
        SecurityProperties.JwtConfig jwt = securityProperties.getJwt();
        String secret = jwt.getSecret();
        validateSecret(secret);
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenValidityMs = jwt.getAccessTokenValidityMs();
        this.refreshTokenValidityMs = jwt.getRefreshTokenValidityMs();
    }

    private void validateSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "JWT 密钥未配置，请通过环境变量 APP_JWT_SECRET 设置至少 32 字节的强随机密钥");
        }
        if (INSECURE_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                "检测到 JWT 使用内置默认密钥（已随源码公开，可被伪造任意用户令牌）。"
                    + "请通过环境变量 APP_JWT_SECRET 设置至少 32 字节的强随机密钥后再启动。");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "JWT 密钥强度不足（HS256/384 要求至少 " + MIN_SECRET_BYTES + " 字节），请设置更长的 APP_JWT_SECRET");
        }
    }

    /**
     * 生成 access token（短期有效，1小时），携带 role claim 供接口级鉴权。
     */
    public String generateAccessToken(Long userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(userId.toString())
            .claim("username", username)
            .claim("role", role)
            .claim("type", ACCESS_TOKEN_TYPE)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(accessTokenValidityMs)))
            .signWith(secretKey)
            .compact();
    }

    /**
     * 生成 refresh token（长期有效，30天）
     */
    public String generateRefreshToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(userId.toString())
            .claim("type", REFRESH_TOKEN_TYPE)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(refreshTokenValidityMs)))
            .signWith(secretKey)
            .compact();
    }

    /**
     * 解析 token 并提取 userId（验证签名和过期时间）
     */
    public Long extractUserId(String token) {
        return extractUserId(token, null);
    }

    public Long extractAccessUserId(String token) {
        return extractUserId(token, ACCESS_TOKEN_TYPE);
    }

    public Long extractRefreshUserId(String token) {
        return extractUserId(token, REFRESH_TOKEN_TYPE);
    }

    private Long extractUserId(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            if (expectedType != null && !expectedType.equals(claims.get("type", String.class))) {
                return null;
            }
            return Long.parseLong(claims.getSubject());
        } catch (Exception e) {
            log.debug("Failed to parse JWT: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 access token 提取 role claim（签名/类型校验失败或无 role 时返回 null，调用方按非管理员处理）。
     */
    public String extractAccessRole(String token) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            if (!ACCESS_TOKEN_TYPE.equals(claims.get("type", String.class))) {
                return null;
            }
            return claims.get("role", String.class);
        } catch (Exception e) {
            log.debug("Failed to extract role from JWT: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 验证 token 是否有效（签名正确、未过期）
     */
    public boolean validateToken(String token) {
        return extractAccessUserId(token) != null;
    }
}
