package com.linrun.interview.modules.jobinterview.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.evidence.EvidenceStatus;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.infrastructure.file.FileHashService;
import com.linrun.interview.modules.jobinterview.config.JobInterviewProperties;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.CommandResult;
import com.linrun.interview.modules.jobinterview.mapper.InterviewCodeDraftMapper;
import com.linrun.interview.modules.jobinterview.mapper.InterviewCommandMapper;
import com.linrun.interview.modules.jobinterview.mapper.InterviewSessionEventMapper;
import com.linrun.interview.modules.jobinterview.mapper.JobInterviewAnswerMapper;
import com.linrun.interview.modules.jobinterview.mapper.JobInterviewQuestionMapper;
import com.linrun.interview.modules.jobinterview.mapper.JobInterviewSessionMapper;
import com.linrun.interview.modules.jobinterview.model.AnswerAssessmentStatus;
import com.linrun.interview.modules.jobinterview.model.InterviewCodeDraftEntity;
import com.linrun.interview.modules.jobinterview.model.InterviewCommandEntity;
import com.linrun.interview.modules.jobinterview.model.InterviewCommandStatus;
import com.linrun.interview.modules.jobinterview.model.InterviewCommandType;
import com.linrun.interview.modules.jobinterview.model.InterviewSessionEventEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewAnswerEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewQuestionEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewSessionEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewSessionStatus;
import com.linrun.interview.modules.jobinterview.model.JobInterviewStage;
import com.linrun.interview.modules.jobinterview.model.QuestionStatus;
import com.linrun.interview.modules.jobinterview.model.RecommendedAction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 岗位实战短事务状态机；外部 LLM/Judge0 调用必须在本类之外完成。 */
@Service
@RequiredArgsConstructor
public class JobInterviewCommandPersistenceService {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
  };

  private final JobInterviewSessionMapper sessionMapper;
  private final JobInterviewQuestionMapper questionMapper;
  private final JobInterviewAnswerMapper answerMapper;
  private final InterviewCommandMapper commandMapper;
  private final InterviewSessionEventMapper eventMapper;
  private final InterviewCodeDraftMapper codeDraftMapper;
  private final JobInterviewSessionPersistenceService sessionPersistence;
  private final JobInterviewViewAssembler viewAssembler;
  private final JobInterviewProperties properties;
  private final FileHashService fileHashService;
  private final ObjectMapper objectMapper;

  @Transactional(rollbackFor = Exception.class)
  public CommandReservation reserve(
      Long userId,
      String sessionId,
      String commandId,
      InterviewCommandType type,
      long expectedVersion
  ) {
    requireCommandArguments(userId, sessionId, commandId, type, expectedVersion);
    InterviewCommandEntity existing = findCommand(userId, sessionId, commandId);
    if (existing != null) {
      validateSameCommand(existing, type, expectedVersion);
      if (existing.getStatus() == InterviewCommandStatus.COMPLETED) {
        CommandResult result = readResult(existing.getResultJson());
        return new CommandReservation(existing, null, result.asDuplicate(), false);
      }
      if (existing.getStatus() == InterviewCommandStatus.PROCESSING) {
        throw new BusinessException(ErrorCode.INTERVIEW_COMMAND_IN_PROGRESS);
      }
      throw new BusinessException(
          ErrorCode.INTERVIEW_INVALID_STATE,
          "该指令此前执行失败，请使用新的 commandId：" + safe(existing.getFailureDetail()));
    }

    JobInterviewSessionEntity session = sessionPersistence.requireOwned(userId, sessionId);
    if (value(session.getSessionVersion()) != expectedVersion) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_VERSION_CONFLICT);
    }
    validateCommandState(session, type);
    int claimed = sessionMapper.claimCommand(
        session.getId(), userId, expectedVersion, commandId);
    if (claimed == 0) {
      JobInterviewSessionEntity current = sessionPersistence.requireOwned(userId, sessionId);
      if (current.getActiveCommandId() != null) {
        throw new BusinessException(ErrorCode.INTERVIEW_COMMAND_IN_PROGRESS);
      }
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_VERSION_CONFLICT);
    }

    LocalDateTime now = LocalDateTime.now();
    InterviewCommandEntity command = InterviewCommandEntity.builder()
        .userId(userId)
        .sessionId(sessionId)
        .commandId(commandId)
        .commandType(type)
        .expectedSessionVersion(expectedVersion)
        .status(InterviewCommandStatus.PROCESSING)
        .createdAt(now)
        .updatedAt(now)
        .build();
    commandMapper.insert(command);
    session.setActiveCommandId(commandId);
    return new CommandReservation(command, session, null, true);
  }

  @Transactional(rollbackFor = Exception.class)
  public CommandExecution completeStart(CommandReservation reservation) {
    Claimed claimed = requireClaimed(reservation, JobInterviewSessionStatus.READY);
    JobInterviewSessionEntity session = claimed.session();
    JobInterviewQuestionEntity question = requireCurrent(session);
    if (question.getStatus() != QuestionStatus.PLANNED) {
      throw invalidState("首题状态不是 PLANNED");
    }
    LocalDateTime now = LocalDateTime.now();
    question.setStatus(QuestionStatus.ASKED);
    questionMapper.updateById(question);
    session.setStatus(JobInterviewSessionStatus.IN_PROGRESS);
    session.setStartedAt(now);
    session.setStageStartedAt(now);
    session.setCurrentStage(question.getStage());
    session.setCurrentQuestionIndex(question.getQuestionIndex());
    session.setStageDeadlineAt(now.plus(question.getStage().budget()));
    session.setSoftDeadlineAt(now.plus(totalBudgetFrom(question.getStage()))
        .plusMinutes(properties.getNaturalCloseMinutes()));
    session.setLastActivityAt(now);
    session.setPausedAt(null);
    session.setResumeExpiresAt(null);
    return finishCommand(
        claimed, session, "SESSION_STARTED", "岗位实战已开始", null, false,
        eventPayload(session, question));
  }

  @Transactional(rollbackFor = Exception.class)
  public CommandExecution completeAnswer(
      CommandReservation reservation,
      Long questionId,
      String answer,
      JobInterviewAssessmentPort.AssessmentOutcome outcome
  ) {
    Claimed claimed = requireClaimed(reservation, JobInterviewSessionStatus.IN_PROGRESS);
    JobInterviewSessionEntity session = claimed.session();
    JobInterviewQuestionEntity question = requireCurrent(session);
    requireMatchingQuestion(question, questionId);
    if (question.getStage() == JobInterviewStage.ALGORITHM) {
      throw invalidState("算法阶段必须提交可执行代码");
    }
    if (question.getStatus() != QuestionStatus.ASKED) {
      throw invalidState("当前问题不可作答");
    }
    LocalDateTime now = LocalDateTime.now();
    insertAnswer(session, question, claimed.command(), answer, outcome, now);
    question.setStatus(QuestionStatus.ANSWERED);
    question.setAnsweredAt(now);
    questionMapper.updateById(question);
    advance(session, question, outcome, now);
    Map<String, Object> payload = eventPayload(session, current(session));
    payload.put("answeredQuestionId", question.getId());
    payload.put("recommendedAction", outcome.assessment().recommendedAction().name());
    payload.put("assessmentStatus", outcome.assessment().pendingReview()
        ? AnswerAssessmentStatus.NEEDS_REVIEW.name() : AnswerAssessmentStatus.COMPLETED.name());
    return finishCommand(
        claimed, session,
        session.getStatus() == JobInterviewSessionStatus.COMPLETED
            ? "SESSION_COMPLETED" : "ANSWER_ASSESSED",
        session.getStatus() == JobInterviewSessionStatus.COMPLETED
            ? "全部阶段已完成" : "回答已记录并完成证据化评价",
        outcome, session.getStatus() == JobInterviewSessionStatus.COMPLETED, payload);
  }

  @Transactional(rollbackFor = Exception.class)
  public CommandExecution completeClarification(
      CommandReservation reservation,
      Long questionId,
      JobInterviewAssessmentPort.ClarificationOutcome clarification
  ) {
    Claimed claimed = requireClaimed(reservation, JobInterviewSessionStatus.IN_PROGRESS);
    JobInterviewSessionEntity session = claimed.session();
    JobInterviewQuestionEntity question = requireCurrent(session);
    if (questionId != null) {
      requireMatchingQuestion(question, questionId);
    }
    session.setLastActivityAt(LocalDateTime.now());
    Map<String, Object> payload = eventPayload(session, question);
    payload.put("message", clarification.message());
    if (clarification.degradedReason() != null) {
      payload.put("degradedReason", clarification.degradedReason());
    }
    return finishCommand(
        claimed, session, "CLARIFICATION", clarification.message(), null, false, payload);
  }

  @Transactional(rollbackFor = Exception.class)
  public CommandExecution completeSaveCode(
      CommandReservation reservation,
      Long questionId,
      String sourceCode
  ) {
    Claimed claimed = requireClaimed(reservation, JobInterviewSessionStatus.IN_PROGRESS);
    JobInterviewSessionEntity session = claimed.session();
    JobInterviewQuestionEntity question = requireCurrentAlgorithmQuestion(session, questionId);
    LocalDateTime now = LocalDateTime.now();
    upsertCodeDraft(session, question, claimed.command(), sourceCode, "DRAFT", null, null, now);
    session.setLastActivityAt(now);
    Map<String, Object> payload = eventPayload(session, question);
    payload.put("sourceHash", hash(sourceCode));
    return finishCommand(
        claimed, session, "CODE_DRAFT_SAVED", "代码草稿已保存", null, false, payload);
  }

  @Transactional(rollbackFor = Exception.class)
  public CommandExecution completeSubmitCode(
      CommandReservation reservation,
      Long questionId,
      String sourceCode,
      JobInterviewCodingPort.CodingOutcome outcome
  ) {
    Claimed claimed = requireClaimed(reservation, JobInterviewSessionStatus.IN_PROGRESS);
    JobInterviewSessionEntity session = claimed.session();
    JobInterviewQuestionEntity question = requireCurrentAlgorithmQuestion(session, questionId);
    LocalDateTime now = LocalDateTime.now();
    upsertCodeDraft(
        session, question, claimed.command(), sourceCode, outcome.status(),
        outcome.submissionId(), writeJson(outcome), now);
    insertCodeAnswer(session, question, claimed.command(), sourceCode, outcome, now);
    question.setStatus(QuestionStatus.ANSWERED);
    question.setAnsweredAt(now);
    questionMapper.updateById(question);
    advance(session, question, null, now);
    Map<String, Object> payload = eventPayload(session, current(session));
    payload.put("answeredQuestionId", question.getId());
    payload.put("judgeStatus", outcome.status());
    payload.put("pendingRejudge", outcome.pendingRejudge());
    if (outcome.submissionId() != null) {
      payload.put("submissionId", outcome.submissionId());
    }
    return finishCommand(
        claimed, session,
        session.getStatus() == JobInterviewSessionStatus.COMPLETED
            ? "SESSION_COMPLETED" : "CODE_JUDGED",
        outcome.pendingRejudge()
            ? "源码已保存，判题暂不可用，可稍后补判"
            : "代码已完成客观判题",
        null, session.getStatus() == JobInterviewSessionStatus.COMPLETED, payload);
  }

  @Transactional(rollbackFor = Exception.class)
  public CommandExecution completeContinue(CommandReservation reservation) {
    Claimed claimed = requireClaimed(reservation, JobInterviewSessionStatus.PAUSED);
    JobInterviewSessionEntity session = claimed.session();
    LocalDateTime now = LocalDateTime.now();
    if (session.getResumeExpiresAt() == null || now.isAfter(session.getResumeExpiresAt())
        || integer(session.getContinuationCount()) >= 1) {
      throw new BusinessException(ErrorCode.INTERVIEW_RESUME_LIMIT_REACHED);
    }
    JobInterviewQuestionEntity question = requireCurrent(session);
    session.setStatus(JobInterviewSessionStatus.IN_PROGRESS);
    session.setContinuationCount(integer(session.getContinuationCount()) + 1);
    session.setPausedAt(null);
    session.setResumeExpiresAt(null);
    session.setStageStartedAt(now);
    session.setStageDeadlineAt(now.plus(session.getCurrentStage().budget()));
    session.setSoftDeadlineAt(now.plus(totalBudgetFrom(session.getCurrentStage()))
        .plusMinutes(properties.getNaturalCloseMinutes()));
    session.setLastActivityAt(now);
    return finishCommand(
        claimed, session, "SESSION_CONTINUED", "已恢复岗位实战", null, false,
        eventPayload(session, question));
  }

  @Transactional(rollbackFor = Exception.class)
  public CommandExecution completeFinish(CommandReservation reservation) {
    Claimed claimed = requireClaimed(
        reservation, JobInterviewSessionStatus.IN_PROGRESS, JobInterviewSessionStatus.PAUSED);
    JobInterviewSessionEntity session = claimed.session();
    LocalDateTime now = LocalDateTime.now();
    skipRemaining(session);
    session.setStatus(JobInterviewSessionStatus.COMPLETED);
    session.setCompletedAt(now);
    session.setCurrentQuestionId(null);
    session.setLastActivityAt(now);
    session.setPausedAt(null);
    session.setResumeExpiresAt(null);
    return finishCommand(
        claimed, session, "SESSION_COMPLETED", "已提前交卷，未答题按跳过记录", null, true,
        eventPayload(session, null));
  }

  @Transactional(rollbackFor = Exception.class)
  public CommandExecution completeAbort(
      CommandReservation reservation,
      String reason
  ) {
    Claimed claimed = requireClaimed(
        reservation, JobInterviewSessionStatus.READY, JobInterviewSessionStatus.IN_PROGRESS,
        JobInterviewSessionStatus.PAUSED);
    JobInterviewSessionEntity session = claimed.session();
    LocalDateTime now = LocalDateTime.now();
    session.setStatus(JobInterviewSessionStatus.ABORTED);
    session.setAbortedAt(now);
    session.setLastActivityAt(now);
    session.setPausedAt(null);
    session.setResumeExpiresAt(null);
    Map<String, Object> payload = eventPayload(session, current(session));
    if (reason != null && !reason.isBlank()) {
      payload.put("reason", reason.trim());
    }
    return finishCommand(
        claimed, session, "SESSION_ABORTED", "岗位实战已终止", null, false, payload);
  }

  @Transactional(rollbackFor = Exception.class)
  public void markFailed(CommandReservation reservation, RuntimeException failure) {
    if (reservation == null || !reservation.fresh() || reservation.command() == null
        || reservation.session() == null) {
      return;
    }
    InterviewCommandEntity command = commandMapper.selectById(reservation.command().getId());
    if (command == null || command.getStatus() != InterviewCommandStatus.PROCESSING) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    int released = sessionMapper.releaseCommand(
        reservation.session().getId(), reservation.session().getUserId(),
        reservation.command().getExpectedSessionVersion(), command.getCommandId());
    if (released != 1) {
      return;
    }
    String failureCode = failure instanceof BusinessException business
        ? String.valueOf(business.getCode()) : "INTERNAL_ERROR";
    int failed = commandMapper.failProcessingCommand(
        command.getId(), command.getUserId(), command.getSessionId(), command.getCommandId(),
        command.getExpectedSessionVersion(), failureCode,
        truncate(failure.getMessage(), 500), now);
    if (failed != 1) {
      throw new BusinessException(
          ErrorCode.INTERNAL_ERROR, "岗位实战失败指令状态未能收敛");
    }
  }

  private CommandExecution finishCommand(
      Claimed claimed,
      JobInterviewSessionEntity session,
      String eventType,
      String message,
      JobInterviewAssessmentPort.AssessmentOutcome assessment,
      boolean newlyCompleted,
      Map<String, Object> payload
  ) {
    long nextVersion = claimed.command().getExpectedSessionVersion() + 1L;
    session.setSessionVersion(nextVersion);
    session.setActiveCommandId(null);
    commitSession(claimed.command(), session);
    InterviewSessionEventEntity event = insertEvent(
        session.getUserId(), session.getSessionId(), eventType, nextVersion, payload);
    JobInterviewQuestionEntity current = current(session);
    int answered = sessionPersistence.answeredCount(session.getUserId(), session.getId());
    int total = Math.max(integer(session.getTotalQuestions()),
        sessionPersistence.listQuestions(session.getUserId(), session.getId()).size());
    CommandResult result = new CommandResult(
        claimed.command().getCommandId(), claimed.command().getCommandType(),
        InterviewCommandStatus.COMPLETED, session.getSessionId(), nextVersion,
        session.getStatus(), session.getCurrentStage(), message,
        viewAssembler.question(current), viewAssembler.assessment(assessment), event.getId(), false,
        degradedReasons(session, assessment));
    completeCommand(claimed.command(), result);
    return new CommandExecution(result, newlyCompleted, session.getUserId());
  }

  private void advance(
      JobInterviewSessionEntity session,
      JobInterviewQuestionEntity answered,
      JobInterviewAssessmentPort.AssessmentOutcome outcome,
      LocalDateTime now
  ) {
    maybeInsertFollowUp(session, answered, outcome, now);
    List<JobInterviewQuestionEntity> questions = sessionPersistence.listQuestions(
        session.getUserId(), session.getId());
    boolean stageExpired = session.getStageDeadlineAt() != null
        && !now.isBefore(session.getStageDeadlineAt());
    if (stageExpired) {
      for (JobInterviewQuestionEntity question : questions) {
        if (question.getStatus() == QuestionStatus.PLANNED
            && question.getStage() == answered.getStage()) {
          question.setStatus(QuestionStatus.SKIPPED);
          questionMapper.updateById(question);
        }
      }
    }
    JobInterviewQuestionEntity next = questions.stream()
        .filter(question -> question.getStatus() == QuestionStatus.PLANNED)
        .filter(question -> question.getSortOrder() > answered.getSortOrder())
        .min(Comparator.comparingInt(JobInterviewQuestionEntity::getSortOrder))
        .orElse(null);
    if (next == null) {
      session.setStatus(JobInterviewSessionStatus.COMPLETED);
      session.setCompletedAt(now);
      session.setCurrentQuestionId(null);
      session.setLastActivityAt(now);
      return;
    }
    next.setStatus(QuestionStatus.ASKED);
    questionMapper.updateById(next);
    if (next.getStage() != session.getCurrentStage()) {
      session.setCurrentStage(next.getStage());
      session.setStageStartedAt(now);
      session.setStageDeadlineAt(now.plus(next.getStage().budget()));
    }
    session.setCurrentQuestionId(next.getId());
    session.setCurrentQuestionIndex(next.getQuestionIndex());
    session.setLastActivityAt(now);
  }

  private void maybeInsertFollowUp(
      JobInterviewSessionEntity session,
      JobInterviewQuestionEntity answered,
      JobInterviewAssessmentPort.AssessmentOutcome outcome,
      LocalDateTime now
  ) {
    if (outcome == null || outcome.followUpQuestion() == null
        || outcome.followUpQuestion().isBlank()
        || Boolean.TRUE.equals(answered.getFollowUp())
        || outcome.assessment().pendingReview()
        || outcome.assessment().recommendedAction() == RecommendedAction.SWITCH_TOPIC
        || integer(session.getReflectionCount()) >= properties.getReflectionLimitPerSession()
        || session.getStageDeadlineAt() != null && !now.isBefore(session.getStageDeadlineAt())) {
      return;
    }
    long children = questionMapper.selectCount(
        Wrappers.<JobInterviewQuestionEntity>lambdaQuery()
            .eq(JobInterviewQuestionEntity::getUserId, session.getUserId())
            .eq(JobInterviewQuestionEntity::getSessionId, session.getId())
            .eq(JobInterviewQuestionEntity::getParentQuestionId, answered.getId()));
    if (children >= properties.getFollowUpLimitPerMainQuestion()) {
      return;
    }
    List<JobInterviewQuestionEntity> all = sessionPersistence.listQuestions(
        session.getUserId(), session.getId());
    int nextIndex = all.stream().map(JobInterviewQuestionEntity::getQuestionIndex)
        .max(Integer::compareTo).orElse(-1) + 1;
    JobInterviewQuestionEntity followUp = JobInterviewQuestionEntity.builder()
        .userId(session.getUserId())
        .sessionId(session.getId())
        .questionIndex(nextIndex)
        .sortOrder(answered.getSortOrder() + 1)
        .stage(answered.getStage())
        .questionType("FOLLOW_UP")
        .questionText(truncate(outcome.followUpQuestion(), 500))
        .capabilityAtomId(answered.getCapabilityAtomId())
        .capabilityAtomVersion(answered.getCapabilityAtomVersion())
        .questionTemplateCode(answered.getQuestionTemplateCode())
        .questionTemplateVersion(answered.getQuestionTemplateVersion())
        .rubricCode(answered.getRubricCode())
        .rubricVersion(answered.getRubricVersion())
        .evidenceSnapshotId(answered.getEvidenceSnapshotId())
        .evidenceIdsJson(answered.getEvidenceIdsJson())
        .budgetSeconds(Math.min(integer(answered.getBudgetSeconds()), 180))
        .parentQuestionId(answered.getId())
        .followUp(true)
        .reflectionRounds(1)
        .promptVersion(answered.getPromptVersion())
        .modelSnapshot(answered.getModelSnapshot())
        .status(QuestionStatus.PLANNED)
        .createdAt(now)
        .build();
    questionMapper.insert(followUp);
    session.setTotalQuestions(integer(session.getTotalQuestions()) + 1);
    session.setReflectionCount(integer(session.getReflectionCount()) + 1);
  }

  private void insertAnswer(
      JobInterviewSessionEntity session,
      JobInterviewQuestionEntity question,
      InterviewCommandEntity command,
      String answer,
      JobInterviewAssessmentPort.AssessmentOutcome outcome,
      LocalDateTime now
  ) {
    var assessment = outcome.assessment();
    Integer score = assessment.technicalCorrectness() == null
        ? null : (assessment.technicalCorrectness()
            + value(assessment.completeness())) / 2;
    answerMapper.insert(JobInterviewAnswerEntity.builder()
        .userId(session.getUserId())
        .sessionId(session.getId())
        .questionIndex(question.getQuestionIndex())
        .questionId(question.getId())
        .question(question.getQuestionText())
        .category(question.getStage().name())
        .userAnswer(answer)
        .score(score)
        .feedback(assessment.rationale())
        .commandId(command.getCommandId())
        .assessmentStatus(assessment.pendingReview()
            ? AnswerAssessmentStatus.NEEDS_REVIEW : AnswerAssessmentStatus.COMPLETED)
        .assessmentJson(writeJson(assessment))
        .assessmentConfidence(BigDecimal.valueOf(assessment.confidence()))
        .recommendedAction(assessment.recommendedAction())
        .evidenceStatus(assessment.evidenceStatus())
        .objectiveEvidenceIdsJson(writeJson(assessment.objectiveEvidenceIds()))
        .promptVersion(question.getPromptVersion())
        .modelSnapshot(question.getModelSnapshot())
        .latencyMs(outcome.latencyMs())
        .inputTokens(outcome.inputTokens())
        .outputTokens(outcome.outputTokens())
        .retryCount(outcome.retryCount())
        .degradedReason(outcome.degradedReason())
        .answeredAt(now)
        .build());
  }

  private void insertCodeAnswer(
      JobInterviewSessionEntity session,
      JobInterviewQuestionEntity question,
      InterviewCommandEntity command,
      String sourceCode,
      JobInterviewCodingPort.CodingOutcome outcome,
      LocalDateTime now
  ) {
    boolean accepted = "ACCEPTED".equals(outcome.status());
    Integer score = outcome.pendingRejudge() ? null : accepted ? 100 : 0;
    answerMapper.insert(JobInterviewAnswerEntity.builder()
        .userId(session.getUserId())
        .sessionId(session.getId())
        .questionIndex(question.getQuestionIndex())
        .questionId(question.getId())
        .question(question.getQuestionText())
        .category(question.getStage().name())
        .userAnswer("[代码提交] sha256=" + hash(sourceCode))
        .score(score)
        .feedback(outcome.diagnostic())
        .commandId(command.getCommandId())
        .assessmentStatus(outcome.pendingRejudge()
            ? AnswerAssessmentStatus.NEEDS_REVIEW : AnswerAssessmentStatus.COMPLETED)
        .assessmentJson(writeJson(outcome))
        .assessmentConfidence(outcome.pendingRejudge() ? BigDecimal.ZERO : BigDecimal.ONE)
        .recommendedAction(RecommendedAction.SWITCH_TOPIC)
        .evidenceStatus(EvidenceStatus.NONE)
        .objectiveEvidenceIdsJson("[]")
        .promptVersion(question.getPromptVersion())
        .modelSnapshot("JUDGE0")
        .retryCount(0)
        .degradedReason(outcome.pendingRejudge() ? outcome.failureCode() : null)
        .answeredAt(now)
        .build());
  }

  private void upsertCodeDraft(
      JobInterviewSessionEntity session,
      JobInterviewQuestionEntity question,
      InterviewCommandEntity command,
      String sourceCode,
      String judgeStatus,
      String submissionId,
      String judgeResultJson,
      LocalDateTime now
  ) {
    InterviewCodeDraftEntity draft = codeDraftMapper.selectOne(
        Wrappers.<InterviewCodeDraftEntity>lambdaQuery()
            .eq(InterviewCodeDraftEntity::getUserId, session.getUserId())
            .eq(InterviewCodeDraftEntity::getSessionId, session.getId())
            .eq(InterviewCodeDraftEntity::getQuestionId, question.getId()));
    if (draft == null) {
      draft = InterviewCodeDraftEntity.builder()
          .userId(session.getUserId())
          .sessionId(session.getId())
          .questionId(question.getId())
          .language(session.getCodingLanguage())
          .sourceCode(sourceCode)
          .sourceHash(hash(sourceCode))
          .judgeStatus(judgeStatus)
          .judgeSubmissionId(submissionId)
          .judgeResultJson(judgeResultJson)
          .commandId(command.getCommandId())
          .createdAt(now)
          .updatedAt(now)
          .submittedAt(submissionId == null ? null : now)
          .build();
      codeDraftMapper.insert(draft);
      return;
    }
    draft.setSourceCode(sourceCode);
    draft.setSourceHash(hash(sourceCode));
    draft.setJudgeStatus(judgeStatus);
    draft.setJudgeSubmissionId(submissionId);
    draft.setJudgeResultJson(judgeResultJson);
    draft.setCommandId(command.getCommandId());
    draft.setUpdatedAt(now);
    if (submissionId != null || "UNAVAILABLE".equals(judgeStatus)) {
      draft.setSubmittedAt(now);
    }
    codeDraftMapper.updateById(draft);
  }

  private void skipRemaining(JobInterviewSessionEntity session) {
    for (JobInterviewQuestionEntity question : sessionPersistence.listQuestions(
        session.getUserId(), session.getId())) {
      if (question.getStatus() == QuestionStatus.PLANNED
          || question.getStatus() == QuestionStatus.ASKED) {
        question.setStatus(QuestionStatus.SKIPPED);
        questionMapper.updateById(question);
      }
    }
  }

  private Claimed requireClaimed(
      CommandReservation reservation,
      JobInterviewSessionStatus... allowedStatuses
  ) {
    if (reservation == null || !reservation.fresh() || reservation.command() == null) {
      throw invalidState("指令没有执行权");
    }
    JobInterviewSessionEntity session = sessionPersistence.requireOwned(
        reservation.command().getUserId(), reservation.command().getSessionId());
    if (!reservation.command().getCommandId().equals(session.getActiveCommandId())
        || value(session.getSessionVersion())
            != reservation.command().getExpectedSessionVersion()) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_VERSION_CONFLICT);
    }
    boolean allowed = false;
    for (JobInterviewSessionStatus status : allowedStatuses) {
      allowed |= session.getStatus() == status;
    }
    if (!allowed) {
      throw invalidState("当前状态 " + session.getStatus() + " 不允许该操作");
    }
    return new Claimed(reservation.command(), session);
  }

  private JobInterviewQuestionEntity requireCurrent(JobInterviewSessionEntity session) {
    return sessionPersistence.currentQuestion(session)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND));
  }

  private JobInterviewQuestionEntity current(JobInterviewSessionEntity session) {
    return session.getCurrentQuestionId() == null
        ? null : sessionPersistence.currentQuestion(session).orElse(null);
  }

  private JobInterviewQuestionEntity requireCurrentAlgorithmQuestion(
      JobInterviewSessionEntity session,
      Long questionId
  ) {
    JobInterviewQuestionEntity question = requireCurrent(session);
    requireMatchingQuestion(question, questionId);
    if (question.getStage() != JobInterviewStage.ALGORITHM
        || question.getStatus() != QuestionStatus.ASKED) {
      throw invalidState("当前不是可提交的算法问题");
    }
    return question;
  }

  private void requireMatchingQuestion(JobInterviewQuestionEntity current, Long questionId) {
    if (questionId == null || !questionId.equals(current.getId())) {
      throw new BusinessException(
          ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "只能操作当前问题");
    }
  }

  private void commitSession(
      InterviewCommandEntity command,
      JobInterviewSessionEntity session
  ) {
    int updated = sessionMapper.update(null, Wrappers.<JobInterviewSessionEntity>lambdaUpdate()
        .eq(JobInterviewSessionEntity::getId, session.getId())
        .eq(JobInterviewSessionEntity::getUserId, session.getUserId())
        .eq(JobInterviewSessionEntity::getSessionVersion, command.getExpectedSessionVersion())
        .eq(JobInterviewSessionEntity::getActiveCommandId, command.getCommandId())
        .set(JobInterviewSessionEntity::getStatus, session.getStatus())
        .set(JobInterviewSessionEntity::getSessionVersion, session.getSessionVersion())
        .set(JobInterviewSessionEntity::getActiveCommandId, null)
        .set(JobInterviewSessionEntity::getTotalQuestions, session.getTotalQuestions())
        .set(JobInterviewSessionEntity::getCurrentQuestionIndex,
            session.getCurrentQuestionIndex())
        .set(JobInterviewSessionEntity::getCurrentStage, session.getCurrentStage())
        .set(JobInterviewSessionEntity::getCurrentQuestionId, session.getCurrentQuestionId())
        .set(JobInterviewSessionEntity::getContinuationCount, session.getContinuationCount())
        .set(JobInterviewSessionEntity::getReflectionCount, session.getReflectionCount())
        .set(JobInterviewSessionEntity::getStartedAt, session.getStartedAt())
        .set(JobInterviewSessionEntity::getStageStartedAt, session.getStageStartedAt())
        .set(JobInterviewSessionEntity::getStageDeadlineAt, session.getStageDeadlineAt())
        .set(JobInterviewSessionEntity::getSoftDeadlineAt, session.getSoftDeadlineAt())
        .set(JobInterviewSessionEntity::getLastActivityAt, session.getLastActivityAt())
        .set(JobInterviewSessionEntity::getResumeExpiresAt, session.getResumeExpiresAt())
        .set(JobInterviewSessionEntity::getPausedAt, session.getPausedAt())
        .set(JobInterviewSessionEntity::getCompletedAt, session.getCompletedAt())
        .set(JobInterviewSessionEntity::getAbortedAt, session.getAbortedAt()));
    if (updated != 1) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_VERSION_CONFLICT);
    }
  }

  private InterviewSessionEventEntity insertEvent(
      Long userId,
      String sessionId,
      String eventType,
      long version,
      Map<String, Object> payload
  ) {
    InterviewSessionEventEntity event = InterviewSessionEventEntity.builder()
        .userId(userId)
        .sessionId(sessionId)
        .eventType(eventType)
        .sessionVersion(version)
        .payloadJson(writeJson(payload))
        .createdAt(LocalDateTime.now())
        .build();
    eventMapper.insert(event);
    return event;
  }

  private void completeCommand(InterviewCommandEntity command, CommandResult result) {
    LocalDateTime now = LocalDateTime.now();
    command.setStatus(InterviewCommandStatus.COMPLETED);
    command.setResultJson(writeJson(result));
    command.setUpdatedAt(now);
    command.setCompletedAt(now);
    commandMapper.updateById(command);
  }

  private InterviewCommandEntity findCommand(
      Long userId,
      String sessionId,
      String commandId
  ) {
    return commandMapper.selectOne(Wrappers.<InterviewCommandEntity>lambdaQuery()
        .eq(InterviewCommandEntity::getUserId, userId)
        .eq(InterviewCommandEntity::getSessionId, sessionId)
        .eq(InterviewCommandEntity::getCommandId, commandId));
  }

  private void validateSameCommand(
      InterviewCommandEntity existing,
      InterviewCommandType type,
      long expectedVersion
  ) {
    if (existing.getCommandType() != type
        || value(existing.getExpectedSessionVersion()) != expectedVersion) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "同一 commandId 不能用于不同指令或会话版本");
    }
  }

  private void validateCommandState(
      JobInterviewSessionEntity session,
      InterviewCommandType type
  ) {
    boolean allowed = switch (type) {
      case START -> session.getStatus() == JobInterviewSessionStatus.READY;
      case SUBMIT_ANSWER, REQUEST_CLARIFICATION, SAVE_CODE, SUBMIT_CODE ->
          session.getStatus() == JobInterviewSessionStatus.IN_PROGRESS;
      case CONTINUE -> session.getStatus() == JobInterviewSessionStatus.PAUSED;
      case FINISH -> session.getStatus() == JobInterviewSessionStatus.IN_PROGRESS
          || session.getStatus() == JobInterviewSessionStatus.PAUSED;
      case ABORT -> session.getStatus() == JobInterviewSessionStatus.READY
          || session.getStatus() == JobInterviewSessionStatus.IN_PROGRESS
          || session.getStatus() == JobInterviewSessionStatus.PAUSED;
    };
    if (!allowed) {
      throw invalidState(
          "当前状态 " + session.getStatus() + " 不允许执行 " + type);
    }
  }

  private CommandResult readResult(String json) {
    try {
      return objectMapper.readValue(json, CommandResult.class);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "历史指令结果损坏", e);
    }
  }

  private List<String> degradedReasons(
      JobInterviewSessionEntity session,
      JobInterviewAssessmentPort.AssessmentOutcome outcome
  ) {
    List<String> stored = sessionPersistence.readJson(
        session.getDegradedReasonsJson(), STRING_LIST, List.of());
    List<String> reasons = new ArrayList<>(stored == null ? List.of() : stored);
    if (outcome != null && outcome.degradedReason() != null
        && !outcome.degradedReason().isBlank()) {
      reasons.add(outcome.degradedReason());
    }
    return reasons.stream().distinct().toList();
  }

  private Map<String, Object> eventPayload(
      JobInterviewSessionEntity session,
      JobInterviewQuestionEntity question
  ) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", session.getStatus().name());
    if (session.getCurrentStage() != null) {
      payload.put("stage", session.getCurrentStage().name());
    }
    if (question != null) {
      payload.put("currentQuestionId", question.getId());
      payload.put("questionIndex", question.getQuestionIndex());
    }
    return payload;
  }

  private Duration totalBudgetFrom(JobInterviewStage stage) {
    Duration total = Duration.ZERO;
    for (JobInterviewStage current : JobInterviewStage.values()) {
      if (current.ordinal() >= stage.ordinal()) {
        total = total.plus(current.budget());
      }
    }
    return total;
  }

  private void requireCommandArguments(
      Long userId,
      String sessionId,
      String commandId,
      InterviewCommandType type,
      long expectedVersion
  ) {
    if (userId == null || userId <= 0 || sessionId == null || sessionId.isBlank()
        || commandId == null || commandId.isBlank() || commandId.length() > 64
        || type == null || expectedVersion <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "岗位实战指令参数不完整");
    }
  }

  private BusinessException invalidState(String detail) {
    return new BusinessException(ErrorCode.INTERVIEW_INVALID_STATE, detail);
  }

  private String hash(String sourceCode) {
    return fileHashService.calculateHash(sourceCode.getBytes(StandardCharsets.UTF_8));
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化岗位实战指令失败", e);
    }
  }

  private long value(Long value) {
    return value == null ? 0L : value;
  }

  private int value(Integer value) {
    return value == null ? 0 : value;
  }

  private int integer(Integer value) {
    return value == null ? 0 : value;
  }

  private String safe(String value) {
    return value == null || value.isBlank() ? "未知原因" : value;
  }

  private String truncate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }

  public record CommandReservation(
      InterviewCommandEntity command,
      JobInterviewSessionEntity session,
      CommandResult duplicateResult,
      boolean fresh
  ) {
  }

  public record CommandExecution(
      CommandResult result,
      boolean newlyCompleted,
      Long userId
  ) {
  }

  private record Claimed(
      InterviewCommandEntity command,
      JobInterviewSessionEntity session
  ) {
  }
}
