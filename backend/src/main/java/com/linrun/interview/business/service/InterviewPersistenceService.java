package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.constant.CommonConstants.InterviewDefaults;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.infra.persistence.EntityQueries;
import com.linrun.interview.infra.persistence.MapperUtils;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.mapper.InterviewAnswerMapper;
import com.linrun.interview.business.mapper.InterviewSessionMapper;
import com.linrun.interview.business.vo.HistoricalQuestion;
import com.linrun.interview.business.entity.InterviewAnswerEntity;
import com.linrun.interview.business.vo.InterviewQuestionDTO;
import com.linrun.interview.business.vo.InterviewReportDTO;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.service.InterviewSessionDeletionService;
import com.linrun.interview.business.mapper.ResumeEntityMapper;
import com.linrun.interview.business.entity.ResumeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 面试持久化服务
 * 面试会话和答案的持久化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPersistenceService {

  private final InterviewSessionMapper interviewSessionMapper;
  private final InterviewAnswerMapper interviewAnswerMapper;
  private final ResumeEntityMapper resumeEntityMapper;
  private final ObjectMapper objectMapper;
  private final InterviewSessionDeletionService interviewSessionDeletionService;

  @Transactional(rollbackFor = Exception.class)
  public InterviewSessionEntity saveSession(String sessionId, Long resumeId,
                                            int totalQuestions,
                                            List<InterviewQuestionDTO> questions,
                                            String llmProvider,
                                            String skillId,
                                            String difficulty,
                                            List<Long> knowledgeBaseIds,
                                            String interviewPlanJson) {
    Long userId = UserContext.requireUserId();
    try {
      InterviewSessionEntity session = new InterviewSessionEntity();
      session.setUserId(userId);
      session.setSessionId(sessionId);
      session.setTotalQuestions(totalQuestions);
      session.setCurrentQuestionIndex(0);
      session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
      session.setQuestionsJson(objectMapper.writeValueAsString(questions));
      session.setLlmProvider(llmProvider != null ? llmProvider : "default");
      session.setSkillId(skillId != null ? skillId : InterviewDefaults.SKILL_ID);
      session.setDifficulty(difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY);
      if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
        session.setKnowledgeBaseIdsJson(objectMapper.writeValueAsString(knowledgeBaseIds));
      }
      session.setInterviewPlanJson(interviewPlanJson);
      session.setCreatedAt(LocalDateTime.now());

      if (resumeId != null) {
        ResumeEntity resume = EntityQueries.byUserAndId(
            resumeEntityMapper, userId, resumeId, ResumeEntity::getUserId, ResumeEntity::getId)
          .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
        session.setResume(resume);
      }

      InterviewSessionEntity saved = MapperUtils.save(interviewSessionMapper, session);
      log.info("面试会话已保存: sessionId={}, skillId={}, resumeId={}", sessionId, skillId, resumeId);
      return saved;
    } catch (JsonProcessingException e) {
      log.error("序列化问题列表失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存会话失败", e);
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void updateSessionStatus(String sessionId, InterviewSessionEntity.SessionStatus status) {
    findSessionEntityBySessionId(sessionId).ifPresent(session -> {
      session.setStatus(status);
      if (status == InterviewSessionEntity.SessionStatus.COMPLETED
          || status == InterviewSessionEntity.SessionStatus.EVALUATED) {
        session.setCompletedAt(LocalDateTime.now());
      }
      MapperUtils.save(interviewSessionMapper, session);
    });
  }

  @Transactional(rollbackFor = Exception.class)
  public void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
    findSessionEntityBySessionId(sessionId).ifPresent(session -> {
      session.setEvaluateStatus(status);
      if (error != null) {
        session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
      } else {
        session.setEvaluateError(null);
      }
      MapperUtils.save(interviewSessionMapper, session);
      log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
    });
  }

  /**
   * Clears a previous report so evaluation can run again. Keeps answers.
   */
  @Transactional(rollbackFor = Exception.class)
  public void prepareReevaluation(String sessionId, String evaluateError) {
    InterviewSessionEntity session = findSessionEntityBySessionId(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    session.setOverallScore(null);
    session.setOverallFeedback(null);
    session.setStrengthsJson(null);
    session.setImprovementsJson(null);
    session.setReferenceAnswersJson(null);
    session.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
    session.setEvaluateStatus(AsyncTaskStatus.PENDING);
    if (evaluateError != null && evaluateError.length() > 500) {
      session.setEvaluateError(evaluateError.substring(0, 500));
    } else {
      session.setEvaluateError(evaluateError);
    }
    MapperUtils.save(interviewSessionMapper, session);

    for (InterviewAnswerEntity answer : findAnswersBySessionId(sessionId)) {
      answer.setScore(null);
      answer.setFeedback(null);
      answer.setReferenceAnswer(null);
      answer.setKeyPointsJson(null);
      MapperUtils.save(interviewAnswerMapper, answer);
    }
    log.info("已清空评估报告，等待重评: sessionId={}", sessionId);
  }

  /**
   * 更新会话题目列表（Agent 编排模式动态出题后追加新题）。
   */
  @Transactional(rollbackFor = Exception.class)
  public void updateQuestions(String sessionId, List<InterviewQuestionDTO> questions) {
    findSessionEntityBySessionId(sessionId).ifPresent(session -> {
      try {
        session.setQuestionsJson(objectMapper.writeValueAsString(questions));
      } catch (JsonProcessingException e) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化问题列表失败", e);
      }
      MapperUtils.save(interviewSessionMapper, session);
    });
  }

  /**
   * 把 totalQuestions 同步为实际已出题数（Agent 动态出题 + 提前交卷时，
   * 计划题数与实际出题数不一致，报告/列表按实际数展示）。
   */
  @Transactional(rollbackFor = Exception.class)
  public void syncTotalQuestionsToActual(String sessionId) {
    findSessionEntityBySessionId(sessionId).ifPresent(session -> {
      try {
        List<InterviewQuestionDTO> questions = parseQuestionList(session.getQuestionsJson());
        if (!questions.isEmpty() && session.getTotalQuestions() != null
            && questions.size() < session.getTotalQuestions()) {
          session.setTotalQuestions(questions.size());
          MapperUtils.save(interviewSessionMapper, session);
        }
      } catch (JsonProcessingException e) {
        log.warn("同步实际题数失败（保留计划题数）: sessionId={}", sessionId, e);
      }
    });
  }

  @Transactional(rollbackFor = Exception.class)
  public void updateCurrentQuestionIndex(String sessionId, int index) {
    findSessionEntityBySessionId(sessionId).ifPresent(session -> {
      session.setCurrentQuestionIndex(index);
      session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
      MapperUtils.save(interviewSessionMapper, session);
    });
  }

  @Transactional(rollbackFor = Exception.class)
  public InterviewAnswerEntity saveAnswer(String sessionId, int questionIndex,
                                          String question, String category,
                                          String userAnswer, int score, String feedback) {
    return saveAnswer(sessionId, questionIndex, question, category, userAnswer, score,
        feedback, null);
  }

  @Transactional(rollbackFor = Exception.class)
  public InterviewAnswerEntity saveAnswer(String sessionId, int questionIndex,
                                          String question, String category,
                                          String userAnswer, int score, String feedback,
                                          String commandId) {
    InterviewSessionEntity session = findSessionEntityBySessionId(sessionId)
      .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));

    InterviewAnswerEntity answer = findAnswerBySessionAndIndex(session.getId(), questionIndex)
      .orElseGet(() -> {
        InterviewAnswerEntity created = new InterviewAnswerEntity();
        created.setSessionId(session.getId());
        created.setUserId(session.getUserId());
        created.setQuestionIndex(questionIndex);
        created.setAnsweredAt(LocalDateTime.now());
        return created;
      });

    answer.setUserId(session.getUserId());
    answer.setQuestion(question);
    answer.setCategory(category);
    answer.setUserAnswer(userAnswer);
    answer.setScore(score);
    answer.setFeedback(feedback);
    if (commandId != null) {
      answer.setCommandId(commandId);
    }

    InterviewAnswerEntity saved = MapperUtils.save(interviewAnswerMapper, answer);
    log.info("面试答案已保存: sessionId={}, questionIndex={}, score={}",
        sessionId, questionIndex, score);
    return saved;
  }

  /**
   * Atomic legacy answer commit: answer row, dynamic question snapshot and
   * optimistic session version advance are committed under one transaction.
   */
  @Transactional(rollbackFor = Exception.class)
  public void commitLegacyAnswer(String sessionId, Long userId, long expectedVersion,
                                 String commandId, int questionIndex, String question,
                                 String category, String userAnswer,
                                 List<InterviewQuestionDTO> questions, int newIndex,
                                 InterviewSessionEntity.SessionStatus newStatus) {
    InterviewSessionEntity session = findSessionEntityBySessionId(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    if (!userId.equals(session.getUserId())
        || value(session.getSessionVersion()) != expectedVersion
        || !java.util.Objects.equals(session.getActiveCommandId(), commandId)) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_VERSION_CONFLICT);
    }
    saveAnswer(sessionId, questionIndex, question, category, userAnswer, 0, null, commandId);
    try {
      String questionsJson = objectMapper.writeValueAsString(questions);
      long nextVersion = expectedVersion + 1L;
      int updated = interviewSessionMapper.update(null,
          Wrappers.<InterviewSessionEntity>lambdaUpdate()
              .eq(InterviewSessionEntity::getId, session.getId())
              .eq(InterviewSessionEntity::getUserId, userId)
              .eq(InterviewSessionEntity::getSessionVersion, expectedVersion)
              .eq(InterviewSessionEntity::getActiveCommandId, commandId)
              .set(InterviewSessionEntity::getQuestionsJson, questionsJson)
              .set(InterviewSessionEntity::getCurrentQuestionIndex, newIndex)
              .set(InterviewSessionEntity::getStatus, newStatus)
              .set(InterviewSessionEntity::getSessionVersion, nextVersion)
              .set(InterviewSessionEntity::getActiveCommandId, null)
              .set(InterviewSessionEntity::getCompletedAt,
                  newStatus == InterviewSessionEntity.SessionStatus.COMPLETED
                      ? LocalDateTime.now() : null)
              .set(InterviewSessionEntity::getEvaluateStatus,
                  newStatus == InterviewSessionEntity.SessionStatus.COMPLETED
                      ? AsyncTaskStatus.PENDING : null));
      if (updated != 1) {
        throw new BusinessException(ErrorCode.INTERVIEW_SESSION_VERSION_CONFLICT);
      }
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化问题列表失败", e);
    }
  }

  private long value(Long value) {
    return value == null ? 0L : value;
  }

  @Transactional(rollbackFor = Exception.class)
  public void saveReport(String sessionId, InterviewReportDTO report) {
    try {
      Optional<InterviewSessionEntity> sessionOpt = findSessionEntityBySessionId(sessionId);
      if (sessionOpt.isEmpty()) {
        log.warn("会话不存在: {}", sessionId);
        return;
      }

      InterviewSessionEntity session = sessionOpt.get();
      session.setOverallScore(report.overallScore());
      session.setOverallFeedback(report.overallFeedback());
      session.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
      session.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
      session.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
      session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
      session.setCompletedAt(LocalDateTime.now());
      MapperUtils.save(interviewSessionMapper, session);

      List<InterviewAnswerEntity> existingAnswers = findAnswersBySessionId(sessionId);
      Map<Integer, InterviewAnswerEntity> answerMap = existingAnswers.stream()
        .collect(Collectors.toMap(
          InterviewAnswerEntity::getQuestionIndex,
          a -> a,
          (a1, a2) -> a1
        ));

      Map<Integer, InterviewReportDTO.ReferenceAnswer> refAnswerMap = report.referenceAnswers().stream()
        .collect(Collectors.toMap(
          InterviewReportDTO.ReferenceAnswer::questionIndex,
          r -> r,
          (r1, r2) -> r1
        ));

      List<InterviewAnswerEntity> answersToSave = new ArrayList<>();
      for (InterviewReportDTO.QuestionEvaluation eval : report.questionDetails()) {
        InterviewAnswerEntity answer = answerMap.get(eval.questionIndex());
        if (answer == null) {
          answer = new InterviewAnswerEntity();
          answer.setSessionId(session.getId());
          answer.setUserId(session.getUserId());
          answer.setQuestionIndex(eval.questionIndex());
          answer.setQuestion(eval.question());
          answer.setCategory(eval.category());
          answer.setUserAnswer(null);
          answer.setAnsweredAt(LocalDateTime.now());
          log.debug("为未回答的题目 {} 创建答案记录", eval.questionIndex());
        }

        answer.setUserId(session.getUserId());
        answer.setScore(eval.score());
        answer.setFeedback(eval.feedback());

        InterviewReportDTO.ReferenceAnswer refAns = refAnswerMap.get(eval.questionIndex());
        if (refAns != null) {
          answer.setReferenceAnswer(refAns.referenceAnswer());
          if (refAns.keyPoints() != null && !refAns.keyPoints().isEmpty()) {
            answer.setKeyPointsJson(objectMapper.writeValueAsString(refAns.keyPoints()));
          }
        }
        answersToSave.add(answer);
      }

      for (InterviewAnswerEntity answer : answersToSave) {
        MapperUtils.save(interviewAnswerMapper, answer);
      }
      log.info("面试报告已保存: sessionId={}, score={}, 答案数={}",
          sessionId, report.overallScore(), answersToSave.size());
    } catch (JsonProcessingException e) {
      log.error("序列化报告失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
          "保存面试报告失败", e);
    }
  }

  /**
   * 读取已落库的评估报告（幂等返回）。仅当会话已 EVALUATED 且落有总分时返回，
   * 供 {@code GET /report} 直接复用，避免每次请求都重跑完整 LLM 评估、并与异步评估竞态。
   * 会话未评估时返回空，交由调用方决定是否触发一次同步评估。
   */
  public Optional<InterviewReportDTO> loadStoredReport(String sessionId) {
    return loadStoredReport(findBySessionId(sessionId), sessionId);
  }

  /** 异步消费者使用的内部入口，不依赖请求线程 {@link UserContext}。 */
  public Optional<InterviewReportDTO> loadStoredReportInternal(String sessionId) {
    return loadStoredReport(findBySessionIdInternal(sessionId), sessionId);
  }

  private Optional<InterviewReportDTO> loadStoredReport(
      Optional<InterviewSessionEntity> sessionOpt, String sessionId) {
    if (sessionOpt.isEmpty()) {
      return Optional.empty();
    }
    InterviewSessionEntity session = sessionOpt.get();
    if (session.getStatus() != InterviewSessionEntity.SessionStatus.EVALUATED
        || session.getOverallScore() == null) {
      return Optional.empty();
    }
    try {
      List<InterviewQuestionDTO> questions = parseQuestionList(session.getQuestionsJson());
      Map<Integer, InterviewAnswerEntity> answerByIndex = findAnswersBySessionId(sessionId).stream()
          .collect(Collectors.toMap(InterviewAnswerEntity::getQuestionIndex, a -> a, (a, b) -> a));

      List<InterviewReportDTO.QuestionEvaluation> questionDetails = new ArrayList<>();
      for (InterviewQuestionDTO q : questions) {
        InterviewAnswerEntity ans = answerByIndex.get(q.questionIndex());
        String category = ans != null && ans.getCategory() != null ? ans.getCategory() : q.category();
        questionDetails.add(new InterviewReportDTO.QuestionEvaluation(
            q.questionIndex(), q.question(), category,
            ans != null ? ans.getUserAnswer() : null,
            ans != null ? ans.getScore() : null,
            ans != null ? ans.getFeedback() : null));
      }
      boolean anyAnswered = questionDetails.stream()
          .anyMatch(detail -> detail.userAnswer() != null && !detail.userAnswer().isBlank());
      List<String> questionFeedbacks = questionDetails.stream()
          .map(InterviewReportDTO.QuestionEvaluation::feedback)
          .toList();
      if (!EvaluationQuality.isValidStoredReport(
          session.getOverallFeedback(), anyAnswered, questionFeedbacks)) {
        return Optional.empty();
      }

      return Optional.of(new InterviewReportDTO(
          sessionId,
          questions.size(),
          session.getOverallScore(),
          buildCategoryScores(questionDetails),
          questionDetails,
          session.getOverallFeedback(),
          parseStringList(session.getStrengthsJson()),
          parseStringList(session.getImprovementsJson()),
          parseReferenceAnswers(session.getReferenceAnswersJson())));
    } catch (Exception e) {
      log.warn("读取已存面试报告失败，回退到重新评估: sessionId={}", sessionId, e);
      return Optional.empty();
    }
  }

  private List<InterviewQuestionDTO> parseQuestionList(String json) throws JsonProcessingException {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    return objectMapper.readValue(json, new TypeReference<List<InterviewQuestionDTO>>() {});
  }

  private List<String> parseStringList(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  private List<InterviewReportDTO.ReferenceAnswer> parseReferenceAnswers(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json,
          new TypeReference<List<InterviewReportDTO.ReferenceAnswer>>() {});
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  /** 类别得分由逐题分数按 category 分组求均值重建（评估时未单独落库 categoryScores）。 */
  private List<InterviewReportDTO.CategoryScore> buildCategoryScores(
      List<InterviewReportDTO.QuestionEvaluation> details) {
    Map<String, int[]> agg = new LinkedHashMap<>();
    for (InterviewReportDTO.QuestionEvaluation d : details) {
      if (d.score() == null) {
        continue;
      }
      String category = d.category() == null || d.category().isBlank() ? "综合" : d.category();
      int[] sumCount = agg.computeIfAbsent(category, k -> new int[2]);
      sumCount[0] += d.score();
      sumCount[1] += 1;
    }
    List<InterviewReportDTO.CategoryScore> result = new ArrayList<>();
    for (Map.Entry<String, int[]> entry : agg.entrySet()) {
      int count = entry.getValue()[1];
      int avg = count == 0 ? 0 : Math.round((float) entry.getValue()[0] / count);
      result.add(new InterviewReportDTO.CategoryScore(entry.getKey(), avg, count));
    }
    return result;
  }

  public Optional<InterviewSessionEntity> findBySessionId(String sessionId) {
    return findSessionByUserAndSessionId(UserContext.requireUserId(), sessionId)
      .map(this::attachResumeIfPresent);
  }

  public Optional<InterviewSessionEntity> findBySessionIdInternal(String sessionId) {
    return findSessionEntityBySessionId(sessionId)
      .map(this::attachResumeIfPresent);
  }

  public List<InterviewSessionEntity> findByResumeId(Long resumeId) {
    return interviewSessionMapper.selectList(Wrappers.<InterviewSessionEntity>lambdaQuery()
      .eq(InterviewSessionEntity::getUserId, UserContext.requireUserId())
      .eq(InterviewSessionEntity::getResumeId, resumeId)
      .orderByDesc(InterviewSessionEntity::getCreatedAt));
  }

  public List<InterviewSessionEntity> findAll() {
    return EntityQueries.listByUserIdOrderByDesc(
      interviewSessionMapper,
      UserContext.requireUserId(),
      InterviewSessionEntity::getUserId,
      InterviewSessionEntity::getCreatedAt);
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteSessionsByResumeId(Long resumeId) {
    Long userId = UserContext.requireUserId();
    List<InterviewSessionEntity> sessions = findByResumeId(resumeId);
    for (InterviewSessionEntity session : sessions) {
      interviewSessionDeletionService.deleteOwnedSessionArtifacts(
          userId, session.getId(), session.getSessionId());
      interviewSessionMapper.delete(Wrappers.<InterviewSessionEntity>lambdaQuery()
          .eq(InterviewSessionEntity::getId, session.getId())
          .eq(InterviewSessionEntity::getUserId, userId));
    }
    if (!sessions.isEmpty()) {
      log.info("已删除 {} 个面试会话（包含所有答案）", sessions.size());
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteSessionBySessionId(String sessionId) {
    Long userId = UserContext.requireUserId();
    InterviewSessionEntity session = findSessionByUserAndSessionId(userId, sessionId)
      .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    try {
      interviewSessionDeletionService.deleteOwnedSessionArtifacts(
          userId, session.getId(), session.getSessionId());
      interviewSessionMapper.delete(Wrappers.<InterviewSessionEntity>lambdaQuery()
          .eq(InterviewSessionEntity::getId, session.getId())
          .eq(InterviewSessionEntity::getUserId, userId));
    } catch (BusinessException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR,
          "删除面试记录失败：" + InterviewSessionDeletionService.rootMessage(e), e);
    }
    log.info("已删除面试会话: sessionId={}", sessionId);
  }

  public Optional<InterviewSessionEntity> findUnfinishedSession(Long resumeId) {
    List<InterviewSessionEntity.SessionStatus> unfinishedStatuses = List.of(
      InterviewSessionEntity.SessionStatus.CREATED,
      InterviewSessionEntity.SessionStatus.IN_PROGRESS
    );
    return MapperUtils.selectOneOptional(interviewSessionMapper,
      Wrappers.<InterviewSessionEntity>lambdaQuery()
        .eq(InterviewSessionEntity::getUserId, UserContext.requireUserId())
        .eq(InterviewSessionEntity::getResumeId, resumeId)
        .in(InterviewSessionEntity::getStatus, unfinishedStatuses)
        .orderByDesc(InterviewSessionEntity::getCreatedAt)
        .last("LIMIT 1"))
      .map(this::attachResumeIfPresent);
  }

  public void ensureResumeAccessible(Long resumeId) {
    if (resumeId != null && !EntityQueries.existsByUserAndId(
        resumeEntityMapper, UserContext.requireUserId(), resumeId,
        ResumeEntity::getUserId, ResumeEntity::getId)) {
      throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
    }
  }

  public List<InterviewAnswerEntity> findAnswersBySessionId(String sessionId) {
    return findSessionEntityBySessionId(sessionId)
      .map(session -> interviewAnswerMapper.selectList(
        Wrappers.<InterviewAnswerEntity>lambdaQuery()
          .eq(InterviewAnswerEntity::getSessionId, session.getId())
          .orderByAsc(InterviewAnswerEntity::getQuestionIndex)))
      .orElse(List.of());
  }

  private static final int MAX_HISTORICAL_QUESTIONS = 60;

  public List<HistoricalQuestion> getHistoricalQuestions(String skillId, Long resumeId) {
    Long userId = UserContext.requireUserId();
    ensureResumeAccessible(resumeId);
    List<InterviewSessionEntity> sessions;
    if (resumeId != null) {
      sessions = interviewSessionMapper.selectList(
        Wrappers.<InterviewSessionEntity>lambdaQuery()
          .eq(InterviewSessionEntity::getUserId, userId)
          .eq(InterviewSessionEntity::getResumeId, resumeId)
          .eq(InterviewSessionEntity::getSkillId, skillId)
          .orderByDesc(InterviewSessionEntity::getCreatedAt)
          .last("LIMIT 10"));
    } else {
      sessions = interviewSessionMapper.selectList(
        Wrappers.<InterviewSessionEntity>lambdaQuery()
          .eq(InterviewSessionEntity::getUserId, userId)
          .eq(InterviewSessionEntity::getSkillId, skillId)
          .orderByDesc(InterviewSessionEntity::getCreatedAt)
          .last("LIMIT 10"));
    }

    log.info("加载历史题目: skillId={}, resumeId={}, 查到 {} 个历史会话", skillId, resumeId, sessions.size());

    LinkedHashSet<String> seen = new LinkedHashSet<>();
    List<HistoricalQuestion> result = sessions.stream()
      .map(InterviewSessionEntity::getQuestionsJson)
      .filter(json -> json != null && !json.isEmpty())
      .flatMap(json -> {
        try {
          List<InterviewQuestionDTO> questions = objectMapper.readValue(json,
            new TypeReference<List<InterviewQuestionDTO>>() {});
          return questions.stream()
            .filter(q -> !q.isFollowUp())
            .map(q -> new HistoricalQuestion(q.question(), q.type(), q.topicSummary()));
        } catch (Exception e) {
          log.error("解析历史问题JSON失败", e);
          return Stream.<HistoricalQuestion>empty();
        }
      })
      .filter(hq -> seen.add(hq.question()))
      .limit(MAX_HISTORICAL_QUESTIONS)
      .toList();

    log.info("历史题目加载完成: 去重后 {} 道主问题，按分类: {}", result.size(),
      result.stream().collect(Collectors.groupingBy(
        hq -> hq.type() != null ? hq.type() : "GENERAL",
        Collectors.counting())));

    return result;
  }

  private Optional<InterviewSessionEntity> findSessionEntityBySessionId(String sessionId) {
    return EntityQueries.selectOne(interviewSessionMapper, InterviewSessionEntity::getSessionId, sessionId);
  }

  private Optional<InterviewSessionEntity> findSessionByUserAndSessionId(Long userId, String sessionId) {
    return MapperUtils.selectOneOptional(interviewSessionMapper,
      Wrappers.<InterviewSessionEntity>lambdaQuery()
        .eq(InterviewSessionEntity::getUserId, userId)
        .eq(InterviewSessionEntity::getSessionId, sessionId));
  }

  private Optional<InterviewAnswerEntity> findAnswerBySessionAndIndex(Long sessionPkId, int questionIndex) {
    return MapperUtils.selectOneOptional(interviewAnswerMapper,
      Wrappers.<InterviewAnswerEntity>lambdaQuery()
        .eq(InterviewAnswerEntity::getSessionId, sessionPkId)
        .eq(InterviewAnswerEntity::getQuestionIndex, questionIndex));
  }

  private InterviewSessionEntity attachResumeIfPresent(InterviewSessionEntity session) {
    if (session.getResumeId() != null) {
      // 只挂当前会话所属用户的简历；找不到时保留 resumeId，避免 setResume(null) 清掉外键。
      EntityQueries.byUserAndId(
          resumeEntityMapper, session.getUserId(), session.getResumeId(),
          ResumeEntity::getUserId, ResumeEntity::getId)
        .ifPresent(session::setResume);
    }
    return session;
  }
}
