package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.business.vo.JobInterviewContracts.AbortCommand;
import com.linrun.interview.business.vo.JobInterviewContracts.ClarificationCommand;
import com.linrun.interview.business.vo.JobInterviewContracts.CodeCommand;
import com.linrun.interview.business.vo.JobInterviewContracts.CodeDraftView;
import com.linrun.interview.business.vo.JobInterviewContracts.CommandEnvelope;
import com.linrun.interview.business.vo.JobInterviewContracts.CommandResult;
import com.linrun.interview.business.vo.JobInterviewContracts.SessionView;
import com.linrun.interview.business.vo.JobInterviewContracts.SubmitAnswerCommand;
import com.linrun.interview.business.mapper.InterviewCodeDraftMapper;
import com.linrun.interview.business.entity.InterviewCodeDraftEntity;
import com.linrun.interview.business.constant.InterviewCommandType;
import com.linrun.interview.business.vo.AgentTraceStep;
import com.linrun.interview.business.entity.JobInterviewQuestionEntity;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;
import com.linrun.interview.business.constant.JobInterviewStage;
import com.linrun.interview.business.service.JobInterviewCommandPersistenceService
    .CommandExecution;
import com.linrun.interview.business.service.JobInterviewCommandPersistenceService
    .CommandReservation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.linrun.interview.infra.observability.TraceContext;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 显式 userId 的非事务编排：占位短事务 -> 外部调用 -> 完成短事务。 */
@Slf4j
@Service
public class JobInterviewRuntimeService {

  private final JobInterviewCommandPersistenceService commandPersistence;
  private final JobInterviewSessionPersistenceService sessionPersistence;
  private final JobInterviewLifecycleService lifecycleService;
  private final JobInterviewAssessmentPort assessmentPort;
  private final JobInterviewCodingPort codingPort;
  private final JobInterviewViewAssembler viewAssembler;
  private final InterviewCodeDraftMapper codeDraftMapper;
  private final ObjectProvider<JobInterviewCompletionPublisher> completionPublishers;
  private final AgentTraceService agentTraceService;
  private final ConcurrentHashMap<String, AgentRunHandle> activeRuns = new ConcurrentHashMap<>();

  /**
   * 保留旧测试/嵌入式调用方的构造签名；生产构造器显式注入 AgentTraceService。
   */
  @Autowired
  public JobInterviewRuntimeService(
      JobInterviewCommandPersistenceService commandPersistence,
      JobInterviewSessionPersistenceService sessionPersistence,
      JobInterviewLifecycleService lifecycleService,
      JobInterviewAssessmentPort assessmentPort,
      JobInterviewCodingPort codingPort,
      JobInterviewViewAssembler viewAssembler,
      InterviewCodeDraftMapper codeDraftMapper,
      ObjectProvider<JobInterviewCompletionPublisher> completionPublishers,
      AgentTraceService agentTraceService
  ) {
    this.commandPersistence = commandPersistence;
    this.sessionPersistence = sessionPersistence;
    this.lifecycleService = lifecycleService;
    this.assessmentPort = assessmentPort;
    this.codingPort = codingPort;
    this.viewAssembler = viewAssembler;
    this.codeDraftMapper = codeDraftMapper;
    this.completionPublishers = completionPublishers;
    this.agentTraceService = agentTraceService;
  }

  public JobInterviewRuntimeService(
      JobInterviewCommandPersistenceService commandPersistence,
      JobInterviewSessionPersistenceService sessionPersistence,
      JobInterviewLifecycleService lifecycleService,
      JobInterviewAssessmentPort assessmentPort,
      JobInterviewCodingPort codingPort,
      JobInterviewViewAssembler viewAssembler,
      InterviewCodeDraftMapper codeDraftMapper,
      ObjectProvider<JobInterviewCompletionPublisher> completionPublishers
  ) {
    this(commandPersistence, sessionPersistence, lifecycleService, assessmentPort, codingPort,
        viewAssembler, codeDraftMapper, completionPublishers, null);
  }

  public SessionView get(Long userId, String sessionId) {
    JobInterviewSessionEntity session = lifecycleService.reconcileOwned(userId, sessionId);
    return viewAssembler.session(
        session,
        sessionPersistence.currentQuestion(session).orElse(null),
        sessionPersistence.answeredCount(userId, session.getId()),
        sessionPersistence.listQuestions(userId, session.getId()).size());
  }

  public CodeDraftView getCodeDraft(Long userId, String sessionId, Long questionId) {
    JobInterviewSessionEntity session = lifecycleService.reconcileOwned(userId, sessionId);
    JobInterviewQuestionEntity question = sessionPersistence.requireQuestion(
        userId, session.getId(), questionId);
    if (question.getStage() != JobInterviewStage.ALGORITHM) {
      throw new BusinessException(
          ErrorCode.INTERVIEW_INVALID_STATE, "该问题不是算法题");
    }
    InterviewCodeDraftEntity draft = codeDraftMapper.selectOne(
        Wrappers.<InterviewCodeDraftEntity>lambdaQuery()
            .eq(InterviewCodeDraftEntity::getUserId, userId)
            .eq(InterviewCodeDraftEntity::getSessionId, session.getId())
            .eq(InterviewCodeDraftEntity::getQuestionId, questionId));
    if (draft == null) {
      var starter = codingPort.starter(question, session.getCodingLanguage());
      return new CodeDraftView(
          questionId, session.getCodingLanguage(), starter.functionSignature(),
          starter.sourceCode(), null,
          "INITIAL", null, null, null);
    }
    var starter = codingPort.starter(question, draft.getLanguage());
    return new CodeDraftView(
        draft.getQuestionId(), draft.getLanguage(), starter.functionSignature(),
        draft.getSourceCode(), draft.getSourceHash(), draft.getJudgeStatus(),
        draft.getJudgeSubmissionId(), draft.getUpdatedAt(), draft.getSubmittedAt());
  }

  public CommandResult start(
      Long userId,
      String sessionId,
      CommandEnvelope command
  ) {
    lifecycleService.reconcileOwned(userId, sessionId);
    CommandReservation reservation = reserve(
        userId, sessionId, command.commandId(), InterviewCommandType.START,
        command.expectedSessionVersion());
    if (!reservation.fresh()) {
      return reservation.duplicateResult();
    }
    return complete(reservation, () -> commandPersistence.completeStart(reservation));
  }

  public CommandResult submitAnswer(
      Long userId,
      String sessionId,
      SubmitAnswerCommand command
  ) {
    lifecycleService.reconcileOwned(userId, sessionId);
    CommandReservation reservation = reserve(
        userId, sessionId, command.commandId(), InterviewCommandType.SUBMIT_ANSWER,
        command.expectedSessionVersion());
    if (!reservation.fresh()) {
      return reservation.duplicateResult();
    }
    try {
      JobInterviewQuestionEntity question = requireCurrentQuestion(
          userId, reservation.session(), command.questionId(), false);
      var assessment = assessmentPort.assess(
          userId, reservation.session(), question, command.answer());
      return publishIfCompleted(commandPersistence.completeAnswer(
          reservation, command.questionId(), command.answer(), assessment));
    } catch (RuntimeException e) {
      fail(reservation, e);
      throw e;
    }
  }

  public CommandResult clarification(
      Long userId,
      String sessionId,
      ClarificationCommand command
  ) {
    lifecycleService.reconcileOwned(userId, sessionId);
    CommandReservation reservation = reserve(
        userId, sessionId, command.commandId(), InterviewCommandType.REQUEST_CLARIFICATION,
        command.expectedSessionVersion());
    if (!reservation.fresh()) {
      return reservation.duplicateResult();
    }
    try {
      JobInterviewQuestionEntity question = sessionPersistence.currentQuestion(
          reservation.session()).orElseThrow(() -> new BusinessException(
              ErrorCode.INTERVIEW_QUESTION_NOT_FOUND));
      var clarification = assessmentPort.clarify(
          userId, reservation.session(), question, command.question());
      return publishIfCompleted(commandPersistence.completeClarification(
          reservation, question.getId(), clarification));
    } catch (RuntimeException e) {
      fail(reservation, e);
      throw e;
    }
  }

  public CommandResult saveCode(
      Long userId,
      String sessionId,
      CodeCommand command
  ) {
    lifecycleService.reconcileOwned(userId, sessionId);
    CommandReservation reservation = reserve(
        userId, sessionId, command.commandId(), InterviewCommandType.SAVE_CODE,
        command.expectedSessionVersion());
    if (!reservation.fresh()) {
      return reservation.duplicateResult();
    }
    return complete(reservation, () -> commandPersistence.completeSaveCode(
        reservation, command.questionId(), command.sourceCode()));
  }

  public CommandResult submitCode(
      Long userId,
      String sessionId,
      CodeCommand command
  ) {
    lifecycleService.reconcileOwned(userId, sessionId);
    CommandReservation reservation = reserve(
        userId, sessionId, command.commandId(), InterviewCommandType.SUBMIT_CODE,
        command.expectedSessionVersion());
    if (!reservation.fresh()) {
      return reservation.duplicateResult();
    }
    try {
      JobInterviewQuestionEntity question = requireCurrentQuestion(
          userId, reservation.session(), command.questionId(), true);
      var outcome = codingPort.submit(
          userId, sessionId, question, reservation.session().getCodingLanguage(),
          command.commandId(), command.sourceCode());
      return publishIfCompleted(commandPersistence.completeSubmitCode(
          reservation, command.questionId(), command.sourceCode(), outcome));
    } catch (RuntimeException e) {
      fail(reservation, e);
      throw e;
    }
  }

  public CommandResult continueInterview(
      Long userId,
      String sessionId,
      CommandEnvelope command
  ) {
    lifecycleService.reconcileOwned(userId, sessionId);
    CommandReservation reservation = reserve(
        userId, sessionId, command.commandId(), InterviewCommandType.CONTINUE,
        command.expectedSessionVersion());
    if (!reservation.fresh()) {
      return reservation.duplicateResult();
    }
    return complete(reservation, () -> commandPersistence.completeContinue(reservation));
  }

  public CommandResult finish(
      Long userId,
      String sessionId,
      CommandEnvelope command
  ) {
    lifecycleService.reconcileOwned(userId, sessionId);
    CommandReservation reservation = reserve(
        userId, sessionId, command.commandId(), InterviewCommandType.FINISH,
        command.expectedSessionVersion());
    if (!reservation.fresh()) {
      return reservation.duplicateResult();
    }
    return complete(reservation, () -> commandPersistence.completeFinish(reservation));
  }

  public CommandResult abort(
      Long userId,
      String sessionId,
      AbortCommand command
  ) {
    lifecycleService.reconcileOwned(userId, sessionId);
    CommandReservation reservation = reserve(
        userId, sessionId, command.commandId(), InterviewCommandType.ABORT,
        command.expectedSessionVersion());
    if (!reservation.fresh()) {
      return reservation.duplicateResult();
    }
    return complete(reservation, () -> commandPersistence.completeAbort(
        reservation, command.reason()));
  }

  private CommandReservation reserve(
      Long userId,
      String sessionId,
      String commandId,
      InterviewCommandType type,
      long expectedVersion
  ) {
    CommandReservation reservation = commandPersistence.reserve(
        userId, sessionId, commandId, type, expectedVersion);
    if (reservation.fresh() && agentTraceService != null) {
      AgentRunHandle run = agentTraceService.startOrGetQuietly(new ExecutionIdentity(
          userId, sessionId, TraceContext.getTraceId(), commandId,
          "agent-" + UUID.randomUUID(), null), "job." + type.name().toLowerCase());
      if (run == null) {
        log.warn("岗位实战 AgentRun 未返回句柄，继续执行指令: sessionId={}, commandId={}",
            sessionId, commandId);
        return reservation;
      }
      activeRuns.put(runKey(userId, sessionId, commandId), run);
      try {
        commandPersistence.attachAgentRun(reservation, run.runId());
      } catch (RuntimeException traceFailure) {
        // Trace is an observability sink; a failed association must not strand
        // the business command in PROCESSING.
        log.warn("岗位实战 AgentRun 关联写入失败，继续执行指令: sessionId={}, commandId={}",
            sessionId, commandId, traceFailure);
      }
    }
    return reservation;
  }

  private JobInterviewQuestionEntity requireCurrentQuestion(
      Long userId,
      JobInterviewSessionEntity session,
      Long questionId,
      boolean algorithmRequired
  ) {
    JobInterviewQuestionEntity question = sessionPersistence.requireQuestion(
        userId, session.getId(), questionId);
    if (session.getCurrentQuestionId() == null
        || !session.getCurrentQuestionId().equals(question.getId())) {
      throw new BusinessException(
          ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "只能操作当前问题");
    }
    boolean algorithm = question.getStage() == JobInterviewStage.ALGORITHM;
    if (algorithmRequired != algorithm) {
      throw new BusinessException(
          ErrorCode.INTERVIEW_INVALID_STATE,
          algorithmRequired ? "当前不是算法阶段" : "算法阶段必须提交可执行代码");
    }
    return question;
  }

  private CommandResult complete(
      CommandReservation reservation,
      CompletionCall call
  ) {
    try {
      return publishIfCompleted(call.execute());
    } catch (RuntimeException e) {
      fail(reservation, e);
      throw e;
    }
  }

  private void fail(CommandReservation reservation, RuntimeException failure) {
    finishRun(reservation, AgentRunStatus.FAILED, failure == null ? "failed" : failure.getMessage());
    try {
      commandPersistence.markFailed(reservation, failure);
    } catch (Exception persistenceFailure) {
      failure.addSuppressed(persistenceFailure);
      log.error("岗位实战失败指令未能释放: sessionId={}, commandId={}",
          reservation.command().getSessionId(), reservation.command().getCommandId(),
          persistenceFailure);
    }
  }

  private CommandResult publishIfCompleted(CommandExecution execution) {
    if (execution == null || execution.result() == null) {
      return null;
    }
    finishRunByCommand(execution.result().commandId(), execution.userId(), execution.result().sessionId(),
        execution.result().degradedReasons().isEmpty()
            ? AgentRunStatus.COMPLETED : AgentRunStatus.DEGRADED,
        execution.result().message());
    if (!execution.newlyCompleted()) {
      return execution.result();
    }
    completionPublishers.orderedStream().forEach(publisher -> {
      try {
        publisher.publishCompleted(execution.result().sessionId(), execution.userId());
      } catch (Exception e) {
        log.error("岗位实战完成事件发布失败，等待报告补偿: sessionId={}",
            execution.result().sessionId(), e);
      }
    });
    return execution.result();
  }

  private void finishRun(CommandReservation reservation, AgentRunStatus status, String reason) {
    if (reservation == null || reservation.command() == null) {
      return;
    }
    AgentRunHandle run = activeRuns.remove(runKey(reservation.command().getUserId(),
        reservation.command().getSessionId(), reservation.command().getCommandId()));
    if (run != null) {
      agentTraceService.appendQuietly(run, new AgentSpanRecord(
          "span-job-" + UUID.randomUUID(), run.rootSpanId(),
          AgentTraceStep.ROLE_ORCHESTRATOR, "command", "", reason, status.name(), null, null, 1));
      agentTraceService.finishQuietly(run, status, 0L,
          status == AgentRunStatus.COMPLETED ? null : reason, reason);
    }
  }

  private void finishRunByCommand(String commandId, Long userId, String sessionId,
                                  AgentRunStatus status, String reason) {
    AgentRunHandle run = activeRuns.remove(runKey(userId, sessionId, commandId));
    if (run != null) {
      agentTraceService.appendQuietly(run, new AgentSpanRecord(
          "span-job-" + UUID.randomUUID(), run.rootSpanId(),
          AgentTraceStep.ROLE_ORCHESTRATOR, "command", "", reason, status.name(), null, null, 1));
      agentTraceService.finishQuietly(run, status, 0L,
          status == AgentRunStatus.COMPLETED ? null : reason, reason);
    }
  }

  private String runKey(Long userId, String sessionId, String commandId) {
    return userId + ":" + sessionId + ":" + commandId;
  }

  @FunctionalInterface
  private interface CompletionCall {
    CommandExecution execute();
  }
}
