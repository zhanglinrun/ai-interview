package com.linrun.interview.modules.jobinterview.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.github.model.GithubRepositoryEntity;
import com.linrun.interview.modules.interview.model.InterviewQuestionDTO;
import com.linrun.interview.modules.jobinterview.mapper.InterviewSessionEventMapper;
import com.linrun.interview.modules.jobinterview.mapper.JobInterviewAnswerMapper;
import com.linrun.interview.modules.jobinterview.mapper.JobInterviewQuestionMapper;
import com.linrun.interview.modules.jobinterview.mapper.JobInterviewSessionMapper;
import com.linrun.interview.modules.jobinterview.model.InterviewSessionEventEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewAnswerEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewPlanSnapshot;
import com.linrun.interview.modules.jobinterview.model.JobInterviewQuestionEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewSessionEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewSessionStatus;
import com.linrun.interview.modules.jobinterview.model.PreparationRunEntity;
import com.linrun.interview.modules.jobinterview.model.QuestionStatus;
import com.linrun.interview.modules.jobtarget.model.JobDescriptionEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JobInterviewSessionPersistenceService {

  private final JobInterviewSessionMapper sessionMapper;
  private final JobInterviewQuestionMapper questionMapper;
  private final JobInterviewAnswerMapper answerMapper;
  private final InterviewSessionEventMapper eventMapper;
  private final ObjectMapper objectMapper;

  @Transactional(rollbackFor = Exception.class)
  public JobInterviewSessionEntity createPreparedSession(
      PreparationRunEntity run,
      JobDescriptionEntity job,
      GithubRepositoryEntity github,
      JobInterviewPlanBuilder.PreparedPlan preparedPlan
  ) {
    JobInterviewSessionEntity existing = sessionMapper.selectOne(
        Wrappers.<JobInterviewSessionEntity>lambdaQuery()
            .eq(JobInterviewSessionEntity::getPreparationRunId, run.getRunId()));
    if (existing != null) {
      return existing;
    }

    JobInterviewPlanSnapshot plan = preparedPlan.snapshot();
    String sessionId = UUID.randomUUID().toString();
    LocalDateTime now = LocalDateTime.now();
    List<InterviewQuestionDTO> compatibilityQuestions = plan.questions().stream()
        .map(question -> InterviewQuestionDTO.createAgent(
            question.questionIndex(), question.question(), question.questionType(),
            stageLabel(question.stage()), question.capabilityAtomId(), false, null,
            question.capabilityAtomId(), "SWITCH_TOPIC", question.evidenceIds()))
        .toList();
    JobInterviewSessionEntity session = JobInterviewSessionEntity.builder()
        .userId(run.getUserId())
        .sessionId(sessionId)
        .skillId(job.getTemplateCode())
        .difficulty("mid")
        .resumeId(run.getResumeId())
        .totalQuestions(plan.questions().size())
        .currentQuestionIndex(0)
        .status(JobInterviewSessionStatus.READY)
        .questionsJson(writeJson(compatibilityQuestions))
        .createdAt(now)
        .llmProvider("BYOK")
        .knowledgeBaseIdsJson(run.getKnowledgeBaseIdsJson())
        .interviewPlanJson(writeJson(preparedPlan.orchestrationPlan()))
        .preparationRunId(run.getRunId())
        .jobDescriptionId(job.getId())
        .jobDescriptionVersion(job.getVersion())
        .capabilityTemplateCode(job.getTemplateCode())
        .capabilityTemplateVersion(job.getTemplateVersion())
        .planVersion(plan.planVersion())
        .promptVersion(plan.promptVersion())
        .evidenceSnapshotId("bundle:" + plan.planVersion())
        .evidenceSnapshotIdsJson(writeJson(plan.evidenceSnapshotIds()))
        .githubRepositoryId(github == null ? null : github.getId())
        .githubCommitSha(github == null ? null : github.getFixedCommitSha())
        .codingLanguage(run.getCodingLanguage())
        .sessionVersion(1L)
        .currentStage(plan.questions().getFirst().stage())
        .personalKnowledgeEnabled(Boolean.TRUE.equals(run.getIncludePersonalMaterials()))
        .degradedReasonsJson(writeJson(plan.degradedReasons()))
        .continuationCount(0)
        .reflectionCount(0)
        .lastActivityAt(now)
        .build();
    sessionMapper.insert(session);

    Long firstQuestionId = null;
    for (var question : plan.questions()) {
      JobInterviewQuestionEntity entity = JobInterviewQuestionEntity.builder()
          .userId(run.getUserId())
          .sessionId(session.getId())
          .questionIndex(question.questionIndex())
          .sortOrder(question.sortOrder())
          .stage(question.stage())
          .questionType(question.questionType())
          .questionText(question.question())
          .capabilityAtomId(question.capabilityAtomId())
          .capabilityAtomVersion(question.capabilityAtomVersion())
          .questionTemplateCode(question.questionTemplateCode())
          .questionTemplateVersion(question.questionTemplateVersion())
          .rubricCode(question.rubricCode())
          .rubricVersion(question.rubricVersion())
          .evidenceSnapshotId(question.evidenceSnapshotId())
          .evidenceIdsJson(writeJson(question.evidenceIds()))
          .budgetSeconds(question.budgetSeconds())
          .followUp(false)
          .reflectionRounds(0)
          .promptVersion(plan.promptVersion())
          .modelSnapshot(plan.modelSnapshot())
          .status(QuestionStatus.PLANNED)
          .createdAt(now)
          .build();
      questionMapper.insert(entity);
      if (firstQuestionId == null) {
        firstQuestionId = entity.getId();
      }
    }
    session.setCurrentQuestionId(firstQuestionId);
    sessionMapper.updateById(session);
    insertEventInternal(
        session.getUserId(), session.getSessionId(), "PREPARATION_READY",
        session.getSessionVersion(), Map.of("status", "READY"));
    return session;
  }

  public JobInterviewSessionEntity requireOwned(Long userId, String sessionId) {
    JobInterviewSessionEntity session = sessionMapper.selectOne(
        Wrappers.<JobInterviewSessionEntity>lambdaQuery()
            .eq(JobInterviewSessionEntity::getUserId, userId)
            .eq(JobInterviewSessionEntity::getSessionId, sessionId)
            .isNotNull(JobInterviewSessionEntity::getPreparationRunId));
    if (session == null) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }
    return session;
  }

  public Optional<JobInterviewSessionEntity> findInternal(String sessionId) {
    return Optional.ofNullable(sessionMapper.selectOne(
        Wrappers.<JobInterviewSessionEntity>lambdaQuery()
            .eq(JobInterviewSessionEntity::getSessionId, sessionId)
            .isNotNull(JobInterviewSessionEntity::getPreparationRunId)));
  }

  public List<JobInterviewQuestionEntity> listQuestions(Long userId, Long sessionPkId) {
    return questionMapper.selectList(Wrappers.<JobInterviewQuestionEntity>lambdaQuery()
        .eq(JobInterviewQuestionEntity::getUserId, userId)
        .eq(JobInterviewQuestionEntity::getSessionId, sessionPkId)
        .orderByAsc(JobInterviewQuestionEntity::getSortOrder));
  }

  public JobInterviewQuestionEntity requireQuestion(
      Long userId,
      Long sessionPkId,
      Long questionId
  ) {
    JobInterviewQuestionEntity question = questionMapper.selectOne(
        Wrappers.<JobInterviewQuestionEntity>lambdaQuery()
            .eq(JobInterviewQuestionEntity::getUserId, userId)
            .eq(JobInterviewQuestionEntity::getSessionId, sessionPkId)
            .eq(JobInterviewQuestionEntity::getId, questionId));
    if (question == null) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND);
    }
    return question;
  }

  public Optional<JobInterviewQuestionEntity> currentQuestion(JobInterviewSessionEntity session) {
    if (session.getCurrentQuestionId() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(questionMapper.selectOne(
        Wrappers.<JobInterviewQuestionEntity>lambdaQuery()
            .eq(JobInterviewQuestionEntity::getUserId, session.getUserId())
            .eq(JobInterviewQuestionEntity::getSessionId, session.getId())
            .eq(JobInterviewQuestionEntity::getId, session.getCurrentQuestionId())));
  }

  public int answeredCount(Long userId, Long sessionPkId) {
    return Math.toIntExact(answerMapper.selectCount(
        Wrappers.<JobInterviewAnswerEntity>lambdaQuery()
            .eq(JobInterviewAnswerEntity::getUserId, userId)
            .eq(JobInterviewAnswerEntity::getSessionId, sessionPkId)));
  }

  public List<InterviewSessionEventEntity> listEvents(
      Long userId,
      String sessionId,
      long afterEventId,
      int limit
  ) {
    return eventMapper.selectList(Wrappers.<InterviewSessionEventEntity>lambdaQuery()
        .eq(InterviewSessionEventEntity::getUserId, userId)
        .eq(InterviewSessionEventEntity::getSessionId, sessionId)
        .gt(InterviewSessionEventEntity::getId, Math.max(0L, afterEventId))
        .orderByAsc(InterviewSessionEventEntity::getId)
        .last("LIMIT " + Math.max(1, limit)));
  }

  @Transactional(rollbackFor = Exception.class)
  public InterviewSessionEventEntity insertEvent(
      Long userId,
      String sessionId,
      String eventType,
      long sessionVersion,
      Map<String, ?> payload
  ) {
    requireOwned(userId, sessionId);
    return insertEventInternal(userId, sessionId, eventType, sessionVersion, payload);
  }

  private InterviewSessionEventEntity insertEventInternal(
      Long userId,
      String sessionId,
      String eventType,
      long sessionVersion,
      Map<String, ?> payload
  ) {
    InterviewSessionEventEntity event = InterviewSessionEventEntity.builder()
        .userId(userId)
        .sessionId(sessionId)
        .eventType(eventType)
        .sessionVersion(sessionVersion)
        .payloadJson(writeJson(payload))
        .createdAt(LocalDateTime.now())
        .build();
    eventMapper.insert(event);
    return event;
  }

  public <T> T readJson(String json, TypeReference<T> type, T fallback) {
    if (json == null || json.isBlank()) {
      return fallback;
    }
    try {
      return objectMapper.readValue(json, type);
    } catch (Exception e) {
      return fallback;
    }
  }

  public String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化岗位实战状态失败", e);
    }
  }

  private String stageLabel(com.linrun.interview.modules.jobinterview.model.JobInterviewStage stage) {
    return switch (stage) {
      case PROJECT_DEEP_DIVE -> "项目 / GitHub 深挖";
      case POSITION_TECH -> "岗位技术与 RAG 场景";
      case ALGORITHM -> "算法";
      case ENGINEERING_SCENARIO -> "岗位工程场景";
    };
  }
}
