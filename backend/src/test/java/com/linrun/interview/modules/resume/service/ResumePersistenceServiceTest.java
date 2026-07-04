package com.linrun.interview.modules.resume.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.infrastructure.file.FileHashService;
import com.linrun.interview.infrastructure.mapper.ResumeMapper;
import com.linrun.interview.infrastructure.redis.RedisService;
import com.linrun.interview.modules.resume.mapper.ResumeAnalysisMapper;
import com.linrun.interview.modules.resume.mapper.ResumeEntityMapper;
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

@DisplayName("简历持久化服务")
class ResumePersistenceServiceTest {

  private final ResumeEntityMapper resumeEntityMapper = mock(ResumeEntityMapper.class);
  private final RedisService redisService = mock(RedisService.class);
  private final ResumePersistenceService service = new ResumePersistenceService(
      resumeEntityMapper,
      mock(ResumeAnalysisMapper.class),
      new ObjectMapper(),
      mock(ResumeMapper.class),
      mock(FileHashService.class),
      redisService);

  @AfterEach
  void tearDown() {
    UserContext.clear();
  }

  @Test
  @DisplayName("不存在的简历 ID 命中空值缓存后不再打 DB")
  void nullCacheSkipsRepeatedMissingIdLookup() {
    UserContext.setUserId(7L);
    when(redisService.get("resume:null:7:99")).thenReturn(null, true);

    assertThat(service.findById(99L)).isEmpty();
    assertThat(service.findById(99L)).isEmpty();

    verify(resumeEntityMapper, times(1)).selectOne(any());
    verify(redisService).set(eq("resume:null:7:99"), eq(true), any(Duration.class));
  }
}
