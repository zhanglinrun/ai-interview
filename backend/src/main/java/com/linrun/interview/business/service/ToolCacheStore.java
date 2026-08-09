package com.linrun.interview.business.service;

import com.linrun.interview.infra.redis.RedisService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ToolCacheStore {
  private final RedisService redisService;

  public String get(String key) {
    return redisService.get(key);
  }

  public void put(String key, String value, Duration ttl) {
    if (value != null) {
      redisService.set(key, value, ttl);
    }
  }
}
