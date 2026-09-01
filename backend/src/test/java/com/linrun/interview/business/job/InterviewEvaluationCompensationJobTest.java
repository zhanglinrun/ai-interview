package com.linrun.interview.business.job;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.business.listener.EvaluateStreamProducer;
import com.linrun.interview.business.mapper.InterviewSessionMapper;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.service.InterviewPersistenceService;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试评估补偿")
class InterviewEvaluationCompensationJobTest {

  @Mock
  private InterviewSessionMapper sessionMapper;
  @Mock
  private EvaluateStreamProducer evaluateStreamProducer;
  @Mock
  private InterviewPersistenceService persistenceService;

  private InterviewEvaluationCompensationJob job;

  @BeforeEach
  void setUp() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "evaluation-compensation-test"),
        InterviewSessionEntity.class);
    job = new InterviewEvaluationCompensationJob(
        sessionMapper, evaluateStreamProducer, persistenceService);
  }

  @Test
  @DisplayName("重派过期评估会话")
  void shouldRequeueStaleInterviewSessions() {
    InterviewSessionEntity first = completedSession("session-1");
    InterviewSessionEntity second = completedSession("session-2");
    when(sessionMapper.selectList(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(first, second));

    job.runEvaluationCompensation();

    verify(evaluateStreamProducer).sendEvaluateTask("session-1");
    verify(evaluateStreamProducer).sendEvaluateTask("session-2");
  }

  @Test
  @DisplayName("降级已评估报告会重派，真全未答 0 分不会")
  void requeuesDegradedEvaluatedButNotUnansweredZero() {
    InterviewSessionEntity degraded = completedSession("degraded-session");
    degraded.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    degraded.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    degraded.setOverallFeedback("8道题均按0分兜底");
    InterviewSessionEntity unanswered = completedSession("unanswered-session");
    unanswered.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    unanswered.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    unanswered.setOverallFeedback("作答率 0/8。");
    when(sessionMapper.selectList(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(degraded, unanswered));

    job.runEvaluationCompensation();

    verify(persistenceService).prepareReevaluation(
        org.mockito.ArgumentMatchers.eq("degraded-session"),
        org.mockito.ArgumentMatchers.any());
    verify(evaluateStreamProducer).sendEvaluateTask("degraded-session");
    verify(evaluateStreamProducer, never()).sendEvaluateTask("unanswered-session");
  }

  private InterviewSessionEntity completedSession(String sessionId) {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId(sessionId);
    session.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
    session.setEvaluateStatus(AsyncTaskStatus.PENDING);
    session.setCompletedAt(LocalDateTime.now().minusMinutes(20));
    return session;
  }
}
