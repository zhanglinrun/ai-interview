package interview.guide.common.security;

import interview.guide.common.config.SecurityProperties;
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

    private final SecretKey secretKey;
    private final long accessTokenValidityMs;
    private final long refreshTokenValidityMs;

    public JwtUtil(SecurityProperties securityProperties) {
        SecurityProperties.JwtConfig jwt = securityProperties.getJwt();
        String secret = jwt.getSecret();
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenValidityMs = jwt.getAccessTokenValidityMs();
        this.refreshTokenValidityMs = jwt.getRefreshTokenValidityMs();
    }

    /**
     * 生成 access token（短期有效，1小时）
     */
    public String generateAccessToken(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(userId.toString())
            .claim("username", username)
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
     * 验证 token 是否有效（签名正确、未过期）
     */
    public boolean validateToken(String token) {
        return extractAccessUserId(token) != null;
    }
}
