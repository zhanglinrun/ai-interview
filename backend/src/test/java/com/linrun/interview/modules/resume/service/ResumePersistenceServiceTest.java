package com.linrun.interview.modules.resume.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.infrastructure.file.FileHashService;
import com.linrun.interview.infrastructure.mapper.ResumeMapper;
import com.linrun.interview.infrastructure.redis.RedisService;
import com.linrun.interview.modules.interview.model.ResumeAnalysisResponse;
import com.linrun.interview.modules.resume.mapper.ResumeAnalysisMapper;
import com.linrun.interview.modules.resume.mapper.ResumeEntityMapper;
import com.linrun.interview.modules.resume.model.ResumeAnalysisEntity;
import com.linrun.interview.modules.resume.model.ResumeEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

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
  private final ResumeAnalysisMapper resumeAnalysisMapper = mock(ResumeAnalysisMapper.class);
  private final ResumeMapper resumeMapper = mock(ResumeMapper.class);
  private final RedisService redisService = mock(RedisService.class);
  private final ResumePersistenceService service = new ResumePersistenceService(
      resumeEntityMapper,
      resumeAnalysisMapper,
      new ObjectMapper(),
      resumeMapper,
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

  @Test
  @DisplayName("保存前截断越界分项并按归一化分项重算总分")
  void saveAnalysisNormalizesScoresBeforeMapping() {
    ResumeEntity resume = ResumeEntity.builder().id(12L).userId(7L).build();
    ResumeAnalysisResponse analysis = analysis(
        99,
        new ResumeAnalysisResponse.ScoreDetail(-3, 18, 25, 15, 44));
    when(resumeMapper.toAnalysisEntity(any())).thenReturn(new ResumeAnalysisEntity());

    service.saveAnalysis(resume, analysis);

    ArgumentCaptor<ResumeAnalysisResponse> captor =
        ArgumentCaptor.forClass(ResumeAnalysisResponse.class);
    verify(resumeMapper).toAnalysisEntity(captor.capture());
    ResumeAnalysisResponse normalized = captor.getValue();
    assertThat(normalized.scoreDetail()).isEqualTo(
        new ResumeAnalysisResponse.ScoreDetail(0, 15, 20, 10, 40));
    assertThat(normalized.overallScore()).isEqualTo(85);
    verify(resumeAnalysisMapper).insert(any(ResumeAnalysisEntity.class));
  }

  @Test
  @DisplayName("分项均合法时仍以分项总和覆盖不一致的模型总分")
  void saveAnalysisKeepsOverallConsistentWithDetails() {
    ResumeEntity resume = ResumeEntity.builder().id(13L).userId(7L).build();
    ResumeAnalysisResponse analysis = analysis(
        100,
        new ResumeAnalysisResponse.ScoreDetail(12, 13, 18, 8, 32));
    when(resumeMapper.toAnalysisEntity(any())).thenReturn(new ResumeAnalysisEntity());

    service.saveAnalysis(resume, analysis);

    ArgumentCaptor<ResumeAnalysisResponse> captor =
        ArgumentCaptor.forClass(ResumeAnalysisResponse.class);
    verify(resumeMapper).toAnalysisEntity(captor.capture());
    assertThat(captor.getValue().scoreDetail()).isEqualTo(analysis.scoreDetail());
    assertThat(captor.getValue().overallScore()).isEqualTo(83);
  }

  private ResumeAnalysisResponse analysis(
      int overallScore,
      ResumeAnalysisResponse.ScoreDetail scoreDetail
  ) {
    return new ResumeAnalysisResponse(
        overallScore,
        scoreDetail,
        "摘要",
        List.of("优势"),
        List.of(),
        "简历正文");
  }
}
