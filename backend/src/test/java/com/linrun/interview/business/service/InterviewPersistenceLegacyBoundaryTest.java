package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.mapper.InterviewAnswerMapper;
import com.linrun.interview.business.mapper.InterviewSessionMapper;
import com.linrun.interview.business.vo.InterviewReportDTO;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.service.JobInterviewSessionDeletionService;
import com.linrun.interview.business.mapper.ResumeEntityMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("旧评估持久化边界")
class InterviewPersistenceLegacyBoundaryTest {

  @Mock
  private InterviewSessionMapper sessionMapper;
  @Mock
  private InterviewAnswerMapper answerMapper;
  @Mock
  private ResumeEntityMapper resumeMapper;
  @Mock
  private JobInterviewSessionDeletionService deletionService;

  private InterviewPersistenceService service;

  @BeforeEach
  void setUp() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "legacy-evaluation-boundary-test"),
        InterviewSessionEntity.class);
    service = new InterviewPersistenceService(
        sessionMapper, answerMapper, resumeMapper, new ObjectMapper(), deletionService);
  }

  @Test
  @DisplayName("旧异步评估入口对岗位实战不可见")
  void shouldHideJobPracticeFromLegacyAsyncEvaluation() {
    InterviewSessionEntity session = session("job-session", "preparation-run-1");
    when(sessionMapper.selectOne(any())).thenReturn(session);

    assertThat(service.findBySessionIdInternal("job-session")).isEmpty();

    verifyNoInteractions(resumeMapper);
  }

  @Test
  @DisplayName("旧版报告不得覆盖岗位实战会话和答案")
  void shouldNotPersistLegacyReportForJobPractice() {
    InterviewSessionEntity session = session("job-session", "preparation-run-1");
    when(sessionMapper.selectOne(any())).thenReturn(session);

    service.saveReport("job-session", report("job-session"));

    verify(sessionMapper, never()).updateById(any(InterviewSessionEntity.class));
    verifyNoInteractions(answerMapper);
    assertThat(session.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.COMPLETED);
    assertThat(session.getOverallScore()).isNull();
  }

  @Test
  @DisplayName("旧模拟面试仍可进入原异步评估链路")
  void shouldKeepLegacyInterviewVisibleToLegacyAsyncEvaluation() {
    InterviewSessionEntity session = session("legacy-session", null);
    when(sessionMapper.selectOne(any())).thenReturn(session);

    assertThat(service.findBySessionIdInternal("legacy-session")).containsSame(session);
  }

  private InterviewSessionEntity session(String sessionId, String preparationRunId) {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setId(11L);
    session.setUserId(7L);
    session.setSessionId(sessionId);
    session.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
    session.setPreparationRunId(preparationRunId);
    return session;
  }

  private InterviewReportDTO report(String sessionId) {
    return new InterviewReportDTO(
        sessionId, 1, 88, List.of(), List.of(), "反馈", List.of(), List.of(), List.of());
  }
}
