package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.rag.model.EvidenceStatus;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.document.service.impl.FileHashService;
import com.linrun.interview.business.config.JobInterviewProperties;
import com.linrun.interview.business.vo.JobInterviewContracts.CommandResult;
import com.linrun.interview.business.mapper.InterviewCodeDraftMapper;
import com.linrun.interview.business.mapper.InterviewCommandMapper;
import com.linrun.interview.business.mapper.InterviewSessionEventMapper;
import com.linrun.interview.business.mapper.JobInterviewAnswerMapper;
import com.linrun.interview.business.mapper.JobInterviewQuestionMapper;
import com.linrun.interview.business.mapper.JobInterviewSessionMapper;
import com.linrun.interview.business.vo.AnswerAssessment;
import com.linrun.interview.business.constant.AnswerAssessmentStatus;
import com.linrun.interview.business.entity.InterviewCodeDraftEntity;
import com.linrun.interview.business.entity.InterviewCommandEntity;
import com.linrun.interview.business.constant.InterviewCommandStatus;
import com.linrun.interview.business.constant.InterviewCommandType;
import com.linrun.interview.business.entity.InterviewSessionEventEntity;
import com.linrun.interview.business.entity.JobInterviewAnswerEntity;
import com.linrun.interview.business.constant.JobCodingLanguage;
import com.linrun.interview.business.entity.JobInterviewQuestionEntity;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;
import com.linrun.interview.business.constant.JobInterviewSessionStatus;
import com.linrun.interview.business.constant.JobInterviewStage;
import com.linrun.interview.business.constant.QuestionStatus;
import com.linrun.interview.business.constant.RecommendedAction;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

@ExtendWith(MockitoExtension.class)
@DisplayName("岗位实战指令状态机")
class JobInterviewCommandPersistenceServiceTest {

  @Mock
  private JobInterviewSessionMapper sessionMapper;
  @Mock
  private JobInterviewQuestionMapper questionMapper;
  @Mock
  private JobInterviewAnswerMapper answerMapper;
  @Mock
  private InterviewCommandMapper commandMapper;
  @Mock
  private InterviewSessionEventMapper eventMapper;
  @Mock
  private InterviewCodeDraftMapper codeDraftMapper;
  @Mock
  private JobInterviewSessionPersistenceService sessionPersistence;

  private ObjectMapper objectMapper;
  private JobInterviewCommandPersistenceService service;

  @BeforeEach
  void setUp() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "job-interview-test"),
        JobInterviewSessionEntity.class);
    objectMapper = new ObjectMapper().findAndRegisterModules();
    JobInterviewProperties properties = new JobInterviewProperties();
    service = new JobInterviewCommandPersistenceService(
        sessionMapper, questionMapper, answerMapper, commandMapper, eventMapper,
        codeDraftMapper, sessionPersistence, new JobInterviewViewAssembler(objectMapper),
        properties, new FileHashService(), objectMapper);
  }

  @Test
  @DisplayName("抢占指令必须同时校验 userId、expectedVersion 和空闲指令槽")
  void shouldClaimCommandWithOptimisticVersion() {
    JobInterviewSessionEntity session = readySession();
    when(commandMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(sessionPersistence.requireOwned(7L, "session-1")).thenReturn(session);
    when(sessionMapper.claimCommand(11L, 7L, 1L, "cmd-start")).thenReturn(1);

    var reservation = service.reserve(
        7L, "session-1", "cmd-start", InterviewCommandType.START, 1L);

    assertThat(reservation.fresh()).isTrue();
    assertThat(reservation.session().getActiveCommandId()).isEqualTo("cmd-start");
    verify(sessionMapper).claimCommand(11L, 7L, 1L, "cmd-start");
    verify(commandMapper).insert(any(InterviewCommandEntity.class));
  }

  @Test
  @DisplayName("旧 expectedVersion 不得创建指令事实")
  void shouldRejectStaleVersionBeforeClaim() {
    JobInterviewSessionEntity session = readySession();
    session.setSessionVersion(3L);
    when(commandMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(sessionPersistence.requireOwned(7L, "session-1")).thenReturn(session);

    assertThatThrownBy(() -> service.reserve(
        7L, "session-1", "stale", InterviewCommandType.START, 2L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("版本冲突");
  }

  @Test
  @DisplayName("租约回收后迟到的旧工作线程不得提交任何业务状态")
  void shouldRejectLateCompletionAfterLeaseRecovery() {
    JobInterviewSessionEntity recovered = readySession();
    recovered.setActiveCommandId(null);
    InterviewCommandEntity staleWorker = command(
        "cmd-stale", InterviewCommandType.START, 1L);
    when(sessionPersistence.requireOwned(7L, "session-1")).thenReturn(recovered);

    assertThatThrownBy(() -> service.completeStart(
        new JobInterviewCommandPersistenceService.CommandReservation(
            staleWorker, recovered, null, true)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("版本冲突");

    verify(questionMapper, never()).updateById(any(JobInterviewQuestionEntity.class));
    verify(sessionMapper, never()).update(eq(null), any(Wrapper.class));
  }

  @Test
  @DisplayName("正常失败应按会话槽再指令状态的 CAS 顺序释放执行权")
  void shouldReleaseClaimBeforeMarkingCommandFailed() {
    JobInterviewSessionEntity session = readySession();
    session.setActiveCommandId("cmd-failed");
    InterviewCommandEntity command = command(
        "cmd-failed", InterviewCommandType.START, 1L);
    when(commandMapper.selectById(31L)).thenReturn(command);
    when(sessionMapper.releaseCommand(11L, 7L, 1L, "cmd-failed")).thenReturn(1);
    when(commandMapper.failProcessingCommand(
        eq(31L), eq(7L), eq("session-1"), eq("cmd-failed"), eq(1L),
        eq("INTERNAL_ERROR"), eq("boom"), any(LocalDateTime.class))).thenReturn(1);

    service.markFailed(
        new JobInterviewCommandPersistenceService.CommandReservation(
            command, session, null, true),
        new IllegalStateException("boom"));

    var order = inOrder(sessionMapper, commandMapper);
    order.verify(sessionMapper).releaseCommand(11L, 7L, 1L, "cmd-failed");
    order.verify(commandMapper).failProcessingCommand(
        eq(31L), eq(7L), eq("session-1"), eq("cmd-failed"), eq(1L),
        eq("INTERNAL_ERROR"), eq("boom"), any(LocalDateTime.class));
  }

  @Test
  @DisplayName("相同 commandId 完成后应返回同一结果并标记 duplicate")
  void shouldReplayCompletedCommand() throws Exception {
    CommandResult stored = new CommandResult(
        "cmd-start", InterviewCommandType.START, InterviewCommandStatus.COMPLETED,
        "session-1", 2L, JobInterviewSessionStatus.IN_PROGRESS,
        JobInterviewStage.PROJECT_DEEP_DIVE, "started", null, null,
        9L, false, List.of());
    InterviewCommandEntity existing = InterviewCommandEntity.builder()
        .id(5L).userId(7L).sessionId("session-1").commandId("cmd-start")
        .commandType(InterviewCommandType.START).expectedSessionVersion(1L)
        .status(InterviewCommandStatus.COMPLETED)
        .resultJson(objectMapper.writeValueAsString(stored)).build();
    when(commandMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

    var replay = service.reserve(
        7L, "session-1", "cmd-start", InterviewCommandType.START, 1L);

    assertThat(replay.fresh()).isFalse();
    assertThat(replay.duplicateResult().duplicate()).isTrue();
    assertThat(replay.duplicateResult().eventId()).isEqualTo(9L);
  }

  @Test
  @DisplayName("开始指令应原子推进首题、阶段预算、会话版本和 SSE 事件")
  @SuppressWarnings("unchecked")
  void shouldStartFirstStageAtomically() {
    JobInterviewSessionEntity session = readySession();
    session.setActiveCommandId("cmd-start");
    JobInterviewQuestionEntity question = question(21L, 0, 100, QuestionStatus.PLANNED);
    InterviewCommandEntity command = command(
        "cmd-start", InterviewCommandType.START, 1L);
    when(sessionPersistence.requireOwned(7L, "session-1")).thenReturn(session);
    when(sessionPersistence.currentQuestion(session)).thenReturn(Optional.of(question));
    when(sessionMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
    when(sessionPersistence.answeredCount(7L, 11L)).thenReturn(0);
    when(sessionPersistence.listQuestions(7L, 11L)).thenReturn(List.of(question));
    when(eventMapper.insert(any(InterviewSessionEventEntity.class))).thenAnswer(invocation -> {
      InterviewSessionEventEntity event = invocation.getArgument(0);
      event.setId(88L);
      return 1;
    });

    var result = service.completeStart(
        new JobInterviewCommandPersistenceService.CommandReservation(
            command, session, null, true));

    assertThat(result.result().sessionVersion()).isEqualTo(2L);
    assertThat(result.result().sessionStatus()).isEqualTo(JobInterviewSessionStatus.IN_PROGRESS);
    assertThat(result.result().currentQuestion().questionId()).isEqualTo(21L);
    assertThat(question.getStatus()).isEqualTo(QuestionStatus.ASKED);
    assertThat(session.getStageDeadlineAt()).isAfter(session.getStageStartedAt());
    assertThat(result.result().eventId()).isEqualTo(88L);
  }

  @Test
  @DisplayName("高置信评价可插入且仅插入一次追问并切换当前题")
  @SuppressWarnings("unchecked")
  void shouldInsertBoundedFollowUpFromAssessment() {
    JobInterviewSessionEntity session = readySession();
    session.setStatus(JobInterviewSessionStatus.IN_PROGRESS);
    session.setSessionVersion(2L);
    session.setActiveCommandId("cmd-answer");
    session.setStartedAt(LocalDateTime.now().minusMinutes(1));
    session.setStageStartedAt(LocalDateTime.now().minusMinutes(1));
    session.setStageDeadlineAt(LocalDateTime.now().plusMinutes(10));
    JobInterviewQuestionEntity question = question(21L, 0, 100, QuestionStatus.ASKED);
    AtomicReference<JobInterviewQuestionEntity> followUp = new AtomicReference<>();
    InterviewCommandEntity command = command(
        "cmd-answer", InterviewCommandType.SUBMIT_ANSWER, 2L);
    when(sessionPersistence.requireOwned(7L, "session-1")).thenReturn(session);
    when(sessionPersistence.currentQuestion(any(JobInterviewSessionEntity.class)))
        .thenAnswer(invocation -> {
          JobInterviewSessionEntity current = invocation.getArgument(0);
          return current.getCurrentQuestionId() != null
                  && current.getCurrentQuestionId().equals(22L)
              ? Optional.ofNullable(followUp.get()) : Optional.of(question);
        });
    when(questionMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
    when(questionMapper.insert(any(JobInterviewQuestionEntity.class))).thenAnswer(invocation -> {
      JobInterviewQuestionEntity inserted = invocation.getArgument(0);
      inserted.setId(22L);
      followUp.set(inserted);
      return 1;
    });
    when(sessionPersistence.listQuestions(7L, 11L)).thenAnswer(invocation ->
        followUp.get() == null ? List.of(question) : List.of(question, followUp.get()));
    when(sessionMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
    when(sessionPersistence.answeredCount(7L, 11L)).thenReturn(1);
    when(eventMapper.insert(any(InterviewSessionEventEntity.class))).thenAnswer(invocation -> {
      InterviewSessionEventEntity event = invocation.getArgument(0);
      event.setId(89L);
      return 1;
    });
    AnswerAssessment assessment = new AnswerAssessment(
        85, 75, "CONSISTENT", EvidenceStatus.SUFFICIENT, 0.9d,
        RecommendedAction.DEEPEN, "主链路正确，但故障恢复还可深入", List.of("e-1"), false);
    var outcome = new JobInterviewAssessmentPort.AssessmentOutcome(
        assessment, "如果消息重复投递，你如何保证状态最终收敛？",
        120L, 100, 40, 0, null);

    var result = service.completeAnswer(
        new JobInterviewCommandPersistenceService.CommandReservation(
            command, session, null, true),
        21L, "通过幂等键和状态机处理", outcome);

    assertThat(result.result().currentQuestion().questionId()).isEqualTo(22L);
    assertThat(result.result().assessment().recommendedAction())
        .isEqualTo(RecommendedAction.DEEPEN);
    assertThat(session.getReflectionCount()).isEqualTo(1);
    assertThat(session.getTotalQuestions()).isEqualTo(2);
    assertThat(followUp.get().getParentQuestionId()).isEqualTo(21L);
    assertThat(followUp.get().getStatus()).isEqualTo(QuestionStatus.ASKED);
    verify(answerMapper).insert(any(JobInterviewAnswerEntity.class));
  }

  @Test
  @DisplayName("阶段预算耗尽时应跳过同阶段余题并切到下一阶段")
  @SuppressWarnings("unchecked")
  void shouldSwitchStageWhenBudgetExpired() {
    JobInterviewSessionEntity session = readySession();
    session.setStatus(JobInterviewSessionStatus.IN_PROGRESS);
    session.setSessionVersion(2L);
    session.setActiveCommandId("cmd-timeout");
    session.setTotalQuestions(3);
    session.setStartedAt(LocalDateTime.now().minusMinutes(20));
    session.setStageStartedAt(LocalDateTime.now().minusMinutes(13));
    session.setStageDeadlineAt(LocalDateTime.now().minusSeconds(1));
    JobInterviewQuestionEntity answered = question(21L, 0, 100, QuestionStatus.ASKED);
    JobInterviewQuestionEntity skipped = question(22L, 1, 200, QuestionStatus.PLANNED);
    JobInterviewQuestionEntity next = question(23L, 2, 300, QuestionStatus.PLANNED);
    next.setStage(JobInterviewStage.POSITION_TECH);
    InterviewCommandEntity command = command(
        "cmd-timeout", InterviewCommandType.SUBMIT_ANSWER, 2L);
    when(sessionPersistence.requireOwned(7L, "session-1")).thenReturn(session);
    when(sessionPersistence.currentQuestion(any(JobInterviewSessionEntity.class)))
        .thenAnswer(invocation -> {
          JobInterviewSessionEntity current = invocation.getArgument(0);
          return current.getCurrentQuestionId() != null
                  && current.getCurrentQuestionId().equals(23L)
              ? Optional.of(next) : Optional.of(answered);
        });
    when(sessionPersistence.listQuestions(7L, 11L))
        .thenReturn(List.of(answered, skipped, next));
    when(sessionMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
    when(sessionPersistence.answeredCount(7L, 11L)).thenReturn(1);
    when(eventMapper.insert(any(InterviewSessionEventEntity.class))).thenAnswer(invocation -> {
      InterviewSessionEventEntity event = invocation.getArgument(0);
      event.setId(90L);
      return 1;
    });
    AnswerAssessment assessment = new AnswerAssessment(
        70, 70, "UNVERIFIED", EvidenceStatus.NONE, 0.8d,
        RecommendedAction.SWITCH_TOPIC, "继续下一阶段", List.of(), false);
    var outcome = new JobInterviewAssessmentPort.AssessmentOutcome(
        assessment, null, 50L, 60, 20, 0, null);

    var result = service.completeAnswer(
        new JobInterviewCommandPersistenceService.CommandReservation(
            command, session, null, true),
        21L, "回答", outcome);

    assertThat(skipped.getStatus()).isEqualTo(QuestionStatus.SKIPPED);
    assertThat(next.getStatus()).isEqualTo(QuestionStatus.ASKED);
    assertThat(result.result().stage()).isEqualTo(JobInterviewStage.POSITION_TECH);
    assertThat(result.result().currentQuestion().questionId()).isEqualTo(23L);
    assertThat(session.getStageDeadlineAt()).isAfter(LocalDateTime.now());
  }

  @Test
  @DisplayName("24 小时窗口内续面应只增加一次 continuationCount 并重建当前阶段预算")
  @SuppressWarnings("unchecked")
  void shouldContinueWithinResumeWindow() {
    JobInterviewSessionEntity session = readySession();
    session.setStatus(JobInterviewSessionStatus.PAUSED);
    session.setSessionVersion(5L);
    session.setActiveCommandId("cmd-continue");
    session.setCurrentStage(JobInterviewStage.POSITION_TECH);
    session.setContinuationCount(0);
    session.setPausedAt(LocalDateTime.now().minusHours(1));
    session.setResumeExpiresAt(LocalDateTime.now().plusHours(23));
    JobInterviewQuestionEntity question = question(21L, 0, 100, QuestionStatus.ASKED);
    question.setStage(JobInterviewStage.POSITION_TECH);
    InterviewCommandEntity command = command(
        "cmd-continue", InterviewCommandType.CONTINUE, 5L);
    when(sessionPersistence.requireOwned(7L, "session-1")).thenReturn(session);
    when(sessionPersistence.currentQuestion(session)).thenReturn(Optional.of(question));
    when(sessionPersistence.listQuestions(7L, 11L)).thenReturn(List.of(question));
    when(sessionPersistence.answeredCount(7L, 11L)).thenReturn(0);
    when(sessionMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
    when(eventMapper.insert(any(InterviewSessionEventEntity.class))).thenAnswer(invocation -> {
      InterviewSessionEventEntity event = invocation.getArgument(0);
      event.setId(91L);
      return 1;
    });

    var result = service.completeContinue(
        new JobInterviewCommandPersistenceService.CommandReservation(
            command, session, null, true));

    assertThat(result.result().sessionStatus())
        .isEqualTo(JobInterviewSessionStatus.IN_PROGRESS);
    assertThat(result.result().sessionVersion()).isEqualTo(6L);
    assertThat(session.getContinuationCount()).isEqualTo(1);
    assertThat(session.getResumeExpiresAt()).isNull();
    assertThat(session.getStageDeadlineAt()).isAfter(LocalDateTime.now());
  }

  @Test
  @DisplayName("Judge0 不可用时仍应保存源码并以待补判完成算法阶段")
  @SuppressWarnings("unchecked")
  void shouldPersistCodeAndPendingRejudge() {
    JobInterviewSessionEntity session = readySession();
    session.setStatus(JobInterviewSessionStatus.IN_PROGRESS);
    session.setSessionVersion(3L);
    session.setActiveCommandId("cmd-code");
    session.setCurrentStage(JobInterviewStage.ALGORITHM);
    session.setStageDeadlineAt(LocalDateTime.now().plusMinutes(10));
    JobInterviewQuestionEntity question = question(21L, 0, 100, QuestionStatus.ASKED);
    question.setStage(JobInterviewStage.ALGORITHM);
    question.setQuestionTemplateVersion("5");
    InterviewCommandEntity command = command(
        "cmd-code", InterviewCommandType.SUBMIT_CODE, 3L);
    when(sessionPersistence.requireOwned(7L, "session-1")).thenReturn(session);
    when(sessionPersistence.currentQuestion(session)).thenReturn(Optional.of(question));
    when(codeDraftMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(sessionPersistence.listQuestions(7L, 11L)).thenReturn(List.of(question));
    when(sessionPersistence.answeredCount(7L, 11L)).thenReturn(1);
    when(sessionMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
    when(eventMapper.insert(any(InterviewSessionEventEntity.class))).thenAnswer(invocation -> {
      InterviewSessionEventEntity event = invocation.getArgument(0);
      event.setId(92L);
      return 1;
    });
    var judge = JobInterviewCodingPort.CodingOutcome.unavailable(
        "JUDGE_NOT_CONFIGURED", "判题服务尚未配置");

    var result = service.completeSubmitCode(
        new JobInterviewCommandPersistenceService.CommandReservation(
            command, session, null, true),
        21L, "class Solution {}", judge);

    assertThat(result.newlyCompleted()).isTrue();
    assertThat(result.result().sessionStatus()).isEqualTo(JobInterviewSessionStatus.COMPLETED);
    ArgumentCaptor<JobInterviewAnswerEntity> answer =
        ArgumentCaptor.forClass(JobInterviewAnswerEntity.class);
    verify(answerMapper).insert(answer.capture());
    assertThat(answer.getValue().getUserAnswer()).startsWith("[代码提交] sha256=");
    assertThat(answer.getValue().getAssessmentStatus())
        .isEqualTo(AnswerAssessmentStatus.NEEDS_REVIEW);
    verify(codeDraftMapper).insert(any(InterviewCodeDraftEntity.class));
  }

  private JobInterviewSessionEntity readySession() {
    return JobInterviewSessionEntity.builder()
        .id(11L).userId(7L).sessionId("session-1").preparationRunId("run-1")
        .status(JobInterviewSessionStatus.READY).sessionVersion(1L)
        .totalQuestions(1).currentQuestionIndex(0).currentQuestionId(21L)
        .currentStage(JobInterviewStage.PROJECT_DEEP_DIVE)
        .codingLanguage(JobCodingLanguage.JAVA21)
        .degradedReasonsJson("[]").continuationCount(0).reflectionCount(0)
        .lastActivityAt(LocalDateTime.now()).build();
  }

  private JobInterviewQuestionEntity question(
      Long id,
      int index,
      int sortOrder,
      QuestionStatus status
  ) {
    return JobInterviewQuestionEntity.builder()
        .id(id).userId(7L).sessionId(11L).questionIndex(index).sortOrder(sortOrder)
        .stage(JobInterviewStage.PROJECT_DEEP_DIVE).questionType("PROJECT")
        .questionText("请讲清消息重复消费的处理链路")
        .capabilityAtomId("MQ_RELIABILITY").capabilityAtomVersion("1.0.0")
        .budgetSeconds(300).followUp(false).reflectionRounds(0)
        .promptVersion("job-interview-v1").modelSnapshot("BYOK:user:7")
        .status(status).createdAt(LocalDateTime.now()).build();
  }

  private InterviewCommandEntity command(
      String commandId,
      InterviewCommandType type,
      long expectedVersion
  ) {
    return InterviewCommandEntity.builder()
        .id(31L).userId(7L).sessionId("session-1").commandId(commandId)
        .commandType(type).expectedSessionVersion(expectedVersion)
        .status(InterviewCommandStatus.PROCESSING).build();
  }
}
