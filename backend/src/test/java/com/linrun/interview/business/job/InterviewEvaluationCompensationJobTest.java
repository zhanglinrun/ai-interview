package com.linrun.interview.business.job;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.business.listener.EvaluateStreamProducer;
import com.linrun.interview.business.mapper.InterviewSessionMapper;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.service.InterviewPersistenceService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("旧面试评估补偿边界")
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
  @DisplayName("只补偿旧模拟面试并双重跳过岗位实战")
  void shouldOnlyRequeueLegacyInterviewSessions() {
    InterviewSessionEntity legacy = completedSession("legacy-session", null);
    InterviewSessionEntity jobPractice = completedSession("job-session", "preparation-run-1");
    when(sessionMapper.selectList(argThat(this::excludesJobPractice)))
        .thenReturn(List.of(legacy, jobPractice));

    job.runEvaluationCompensation();

    verify(evaluateStreamProducer).sendEvaluateTask("legacy-session");
    verify(evaluateStreamProducer, never()).sendEvaluateTask("job-session");
  }

  @Test
  @DisplayName("降级已评估报告会重派，真全未答 0 分不会")
  void requeuesDegradedEvaluatedButNotUnansweredZero() {
    InterviewSessionEntity degraded = completedSession("degraded-session", null);
    degraded.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    degraded.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    degraded.setOverallFeedback("8道题均按0分兜底");
    InterviewSessionEntity unanswered = completedSession("unanswered-session", null);
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

  private InterviewSessionEntity completedSession(String sessionId, String preparationRunId) {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId(sessionId);
    session.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
    session.setEvaluateStatus(AsyncTaskStatus.PENDING);
    session.setCompletedAt(LocalDateTime.now().minusMinutes(20));
    session.setPreparationRunId(preparationRunId);
    return session;
  }

  private boolean excludesJobPractice(Wrapper<InterviewSessionEntity> wrapper) {
    String sql = wrapper.getSqlSegment().replace("`", "").toLowerCase(Locale.ROOT);
    return sql.contains("preparation_run_id is null");
  }
}
