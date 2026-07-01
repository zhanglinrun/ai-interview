package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.infrastructure.redis.RedisService;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 分段文本 Redis 短缓存（对齐 know-engine {@code KnowledgeSegmentServiceImpl#getTextByChunkId}）。
 * 用于父子扩展批量查 parent/brother 时减轻 segment 表压力。
 */
@Service
@RequiredArgsConstructor
public class SegmentTextCacheService {

  private static final String KEY_PREFIX = "kb:segment:text:";
  private static final String EMPTY_MARKER = "__EMPTY__";

  private final RedisService redisService;
  private final KnowledgeBaseQueryProperties queryProperties;

  public String getTextByChunkId(String chunkId, Supplier<String> loader) {
    if (chunkId == null || chunkId.isBlank() || loader == null) {
      return loader != null ? loader.get() : null;
    }
    int ttlSeconds = queryProperties.getParentExpand().getCacheTtlSeconds();
    if (ttlSeconds <= 0) {
      return loader.get();
    }
    String key = KEY_PREFIX + chunkId;
    String cached = redisService.get(key);
    if (cached != null) {
      return EMPTY_MARKER.equals(cached) ? null : cached;
    }
    String loaded = loader.get();
    redisService.set(key, loaded == null || loaded.isBlank() ? EMPTY_MARKER : loaded,
        Duration.ofSeconds(ttlSeconds));
    return loaded;
  }

  public void warmChunkTexts(java.util.Collection<KnowledgeBaseSegmentEntity> segments) {
    if (segments == null || segments.isEmpty()) {
      return;
    }
    for (KnowledgeBaseSegmentEntity segment : segments) {
      if (segment.getChunkId() != null) {
        getTextByChunkId(segment.getChunkId(), segment::getText);
      }
    }
  }
}
