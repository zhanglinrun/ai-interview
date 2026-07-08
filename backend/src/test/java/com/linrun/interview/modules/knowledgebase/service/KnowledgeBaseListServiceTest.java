package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.infrastructure.file.FileStorageService;
import com.linrun.interview.infrastructure.mapper.KnowledgeBaseMapper;
import com.linrun.interview.infrastructure.redis.RedisService;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.modules.knowledgebase.mapper.RagChatMessageMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("知识库列表服务")
class KnowledgeBaseListServiceTest {

  private final KnowledgeBaseEntityMapper entityMapper = mock(KnowledgeBaseEntityMapper.class);
  private final RedisService redisService = mock(RedisService.class);
  private final KnowledgeBaseListService service = new KnowledgeBaseListService(
      entityMapper,
      mock(RagChatMessageMapper.class),
      mock(KnowledgeBaseMapper.class),
      mock(FileStorageService.class),
      redisService);

  @AfterEach
  void tearDown() {
    UserContext.clear();
  }

  @Test
  @DisplayName("不存在的知识库 ID 命中空值缓存后不再打 DB")
  void nullCacheSkipsRepeatedMissingIdLookup() {
    UserContext.setUserId(7L);
    when(redisService.get("kb:null:7:99")).thenReturn(null, true);

    assertThat(service.getKnowledgeBaseEntity(99L)).isEmpty();
    assertThat(service.getKnowledgeBaseEntity(99L)).isEmpty();

    verify(entityMapper, times(1)).selectById(99L);
    verify(redisService).set(eq("kb:null:7:99"), eq(true), any(Duration.class));
  }
}
