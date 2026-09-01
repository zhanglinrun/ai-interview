package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.mapper.InterviewAnswerMapper;
import com.linrun.interview.business.mapper.InterviewSessionMapper;
import com.linrun.interview.business.vo.InterviewReportDTO;
import com.linrun.interview.business.entity.InterviewAnswerEntity;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.service.InterviewSessionDeletionService;
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
@DisplayName("面试评估持久化")
class InterviewPersistenceReportTest {

  @Mock
  private InterviewSessionMapper sessionMapper;
  @Mock
  private InterviewAnswerMapper answerMapper;
  @Mock
  private ResumeEntityMapper resumeMapper;
  @Mock
  private InterviewSessionDeletionService deletionService;

  private InterviewPersistenceService service;

  @BeforeEach
  void setUp() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "legacy-evaluation-boundary-test"),
        InterviewSessionEntity.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "legacy-evaluation-answer-test"),
        InterviewAnswerEntity.class);
    service = new InterviewPersistenceService(
        sessionMapper, answerMapper, resumeMapper, new ObjectMapper(), deletionService);
  }

  @Test
  @DisplayName("异步评估入口可读取当前会话")
  void shouldReadSessionForAsyncEvaluation() {
    InterviewSessionEntity session = session("session-1");
    when(sessionMapper.selectOne(any())).thenReturn(session);

    assertThat(service.findBySessionIdInternal("session-1")).containsSame(session);

  }

  @Test
  @DisplayName("评估报告可以写回当前会话")
  void shouldPersistReportForSession() {
    InterviewSessionEntity session = session("session-1");
    when(sessionMapper.selectOne(any())).thenReturn(session);

    service.saveReport("session-1", report("session-1"));

    verify(sessionMapper).updateById(any(InterviewSessionEntity.class));
    assertThat(session.getOverallScore()).isEqualTo(88);
  }

  @Test
  @DisplayName("0 分加兜底 feedback 的报告不算有效报告")
  void loadStoredReportRejectsDegradedZero() throws Exception {
    InterviewSessionEntity session = session("degraded-session");
    session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    session.setOverallScore(0);
    session.setOverallFeedback("8道题均按0分兜底");
    session.setQuestionsJson(
        "[{\"questionIndex\":0,\"question\":\"Q1\",\"type\":\"JAVA\",\"category\":\"Java\"}]");
    when(sessionMapper.selectOne(any())).thenReturn(session);
    InterviewAnswerEntity answer = new InterviewAnswerEntity();
    answer.setQuestionIndex(0);
    answer.setUserAnswer("很长的技术回答");
    answer.setScore(0);
    answer.setFeedback("该题未成功生成评估结果，系统按 0 分处理。");
    when(answerMapper.selectList(any())).thenReturn(List.of(answer));

    assertThat(service.loadStoredReportInternal("degraded-session")).isEmpty();
  }

  private InterviewSessionEntity session(String sessionId) {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setId(11L);
    session.setUserId(7L);
    session.setSessionId(sessionId);
    session.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
    return session;
  }

  private InterviewReportDTO report(String sessionId) {
    return new InterviewReportDTO(
        sessionId, 1, 88, List.of(), List.of(), "反馈", List.of(), List.of(), List.of());
  }
}
