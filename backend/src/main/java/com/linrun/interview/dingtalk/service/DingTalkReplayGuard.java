package com.linrun.interview.dingtalk.service;

import com.linrun.interview.infra.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Redis + 分布式锁实现回调幂等和重放保护，Redis 不可用时短时本地兜底。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkReplayGuard {

    private static final Duration TTL = Duration.ofMinutes(10);
    private final RedisService redisService;
    private final Map<String, Long> localClaims = new ConcurrentHashMap<>();

    public boolean claim(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return true;
        }
        String key = "dingtalk:callback:dedup:" + messageId;
        try {
            return redisService.executeWithLock(key + ":lock", 1, 5, TimeUnit.SECONDS, () -> {
                if (redisService.exists(key)) {
                    return false;
                }
                redisService.set(key, "DONE", TTL);
                return true;
            });
        } catch (Exception ex) {
            long now = System.currentTimeMillis();
            localClaims.entrySet().removeIf(entry -> entry.getValue() < now);
            return localClaims.putIfAbsent(messageId, now + TTL.toMillis()) == null;
        }
    }
}
