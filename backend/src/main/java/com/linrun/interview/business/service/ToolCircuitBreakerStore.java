package com.linrun.interview.business.service;

import com.linrun.interview.infra.redis.RedisService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Small distributed circuit state backed by Redisson through RedisService. */
@Component
@RequiredArgsConstructor
public class ToolCircuitBreakerStore {
  private static final int FAILURE_THRESHOLD = 3;
  private static final Duration FAILURE_WINDOW = Duration.ofSeconds(30);
  private static final Duration OPEN_DURATION = Duration.ofSeconds(20);

  private final RedisService redisService;

  public boolean isOpen(String toolName) {
    Long openUntil = redisService.get(openKey(toolName));
    if (openUntil == null) {
      return false;
    }
    if (openUntil > System.currentTimeMillis()) {
      return true;
    }
    redisService.delete(openKey(toolName));
    return false;
  }

  public void recordFailure(String toolName) {
    String key = failureKey(toolName);
    long count = redisService.increment(key);
    if (count == 1L) {
      redisService.expire(key, FAILURE_WINDOW);
    }
    if (count >= FAILURE_THRESHOLD) {
      redisService.set(openKey(toolName), System.currentTimeMillis() + OPEN_DURATION.toMillis(),
          OPEN_DURATION);
    }
  }

  public void recordSuccess(String toolName) {
    redisService.delete(failureKey(toolName));
    redisService.delete(openKey(toolName));
  }

  private String failureKey(String toolName) {
    return "agent:tool:circuit:failures:" + toolName;
  }

  private String openKey(String toolName) {
    return "agent:tool:circuit:open:" + toolName;
  }
}
