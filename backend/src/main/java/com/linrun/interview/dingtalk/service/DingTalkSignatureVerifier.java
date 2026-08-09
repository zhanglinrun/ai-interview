package com.linrun.interview.dingtalk.service;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.dingtalk.config.DingTalkProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** 钉钉回调/机器人签名校验，统一处理时间窗和常量时间比较。 */
@Component
@RequiredArgsConstructor
public class DingTalkSignatureVerifier {

    private final DingTalkProperties properties;

    public void verifyCallback(String timestamp, String sign) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "钉钉集成未启用");
        }
        verifyTimestamp(timestamp);
        if (!StringUtils.hasText(properties.getVerificationToken())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "钉钉回调校验 Token 未配置");
        }
        if (!StringUtils.hasText(sign)
            || !constantTimeEquals(sign, hmac(timestamp, properties.getVerificationToken()))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉回调签名无效");
        }
    }

    public String signRobot(String timestamp, String secret) {
        return hmac(timestamp, secret);
    }

    public void verifyTimestamp(String timestamp) {
        long millis;
        try {
            millis = Long.parseLong(Objects.requireNonNull(timestamp, "timestamp"));
            if (Math.abs(millis) < 10_000_000_000L) {
                millis *= 1000;
            }
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉时间戳无效");
        }
        long window = Math.max(1, properties.getReplayWindowSeconds()) * 1000;
        if (Math.abs(Instant.now().toEpochMilli() - millis) > window) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉回调已超出允许时间窗");
        }
    }

    private String hmac(String timestamp, String secret) {
        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(secret)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "钉钉签名参数不完整");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(timestamp.concat("\n").concat(secret)
                .getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "钉钉签名计算失败", ex);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
