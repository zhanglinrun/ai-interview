package com.linrun.interview.modules.interview.service;

import com.linrun.interview.common.constant.CommonConstants.InterviewDefaults;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.infrastructure.redis.InterviewSessionCache;
import com.linrun.interview.infrastructure.redis.InterviewSessionCache.CachedSession;
import com.linrun.interview.infrastructure.redis.RedisChatMemoryStore;
import com.linrun.interview.modules.interview.agent.AgentOrchestrationProperties;
import com.linrun.interview.modules.interview.agent.AgentTraceService;
import com.linrun.interview.modules.interview.agent.model.AgentRunStepEntity;
import com.linrun.interview.modules.interview.agent.model.InterviewPlan;
import com.linrun.interview.modules.interview.agent.orchestrator.InterviewOrchestrator;
import com.linrun.interview.modules.interview.agent.orchestrator.InterviewOrchestrator.GeneratedQuestion;
import com.linrun.interview.modules.interview.listener.EvaluateStreamProducer;
import com.linrun.interview.modules.interview.memory.CandidateMemoryService;
import com.linrun.interview.modules.interview.memory.CandidateMemoryService.CandidateMemoryProfileDTO;
import com.linrun.interview.modules.interview.model.AgentPlanProgressDTO;
import com.linrun.interview.modules.interview.model.AgentTraceGroupDTO;
import com.linrun.interview.modules.interview.model.CreateInterviewRequest;
import com.linrun.interview.modules.interview.model.HistoricalQuestion;
import com.linrun.interview.modules.interview.model.InterviewAnswerEntity;
import com.linrun.interview.modules.interview.model.InterviewQuestionDTO;
import com.linrun.interview.modules.interview.model.InterviewReportDTO;
import com.linrun.interview.modules.interview.model.InterviewSessionDTO;
import com.linrun.interview.modules.interview.model.InterviewSessionEntity;
import com.linrun.interview.modules.interview.model.SubmitAnswerRequest;
import com.linrun.interview.modules.interview.model.SubmitAnswerResponse;
import com.linrun.interview.modules.interview.model.InterviewSessionDTO.SessionStatus;
import com.linrun.interview.modules.interview.skill.InterviewSkillService;
import com.linrun.interview.modules.interview.skill.InterviewSkillService.SkillDTO;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseListService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 面试会话管理服务
 * 管理面试会话的生命周期，使用 Redis 缓存会话状态。
 *
 * <p>出题有两条路径：
 * <ul>
 *   <li>Multi-Agent 编排（默认，{@code app.ai.agent.enabled}）：创建时 Planner 出大纲 +
 *       Interviewer/Critic 出首题，答题后动态生成下一题（Reflexion 反思环）；</li>
 *   <li>批量出题（编排关闭或编排失败降级）：创建时一次性生成全部题目。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionService {

    /** Agent 动态出题的题目 type（历史去重、分类统计用） */
    private static final String AGENT_QUESTION_TYPE = "AGENT";

    private final InterviewQuestionService questionService;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;
    private final EvaluateStreamProducer evaluateStreamProducer;
    private final LlmProviderRegistry llmProviderRegistry;
    private final KnowledgeBaseListService knowledgeBaseListService;
    private final InterviewSkillService skillService;
    private final InterviewOrchestrator orchestrator;
    private final AgentOrchestrationProperties agentProperties;
    private final RedisChatMemoryStore chatMemoryStore;
    private final CandidateMemoryService candidateMemoryService;
    private final AgentTraceService agentTraceService;

    /**
     * 校验知识库 ID 归属当前用户，返回去重后的合法列表；
     * 存在越权或不存在的 ID 时抛业务异常（而不是静默丢弃用户的选择）。
     */
    private List<Long> validateKnowledgeBases(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinctIds = knowledgeBaseIds.stream().distinct().toList();
        Map<Long, String> accessible = knowledgeBaseListService.getKnowledgeBaseNameMap(distinctIds);
        List<Long> missing = distinctIds.stream()
            .filter(id -> !accessible.containsKey(id))
            .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                "知识库不存在或无权访问: " + missing);
        }
        return distinctIds;
    }

    /**
     * 创建新的面试会话
     * 注意：如果已有未完成的会话，不会创建新的，而是返回现有会话
     * 前端应该先调用 findUnfinishedSession 检查，或者使用 forceCreate 参数强制创建
     */
    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        Long userId = UserContext.requireUserId();
        persistenceService.ensureResumeAccessible(request.resumeId());
        // 如果指定了resumeId且未强制创建，检查是否有未完成的会话
        if (request.resumeId() != null && !Boolean.TRUE.equals(request.forceCreate())) {
            Optional<InterviewSessionDTO> unfinishedOpt = findUnfinishedSession(request.resumeId());
            if (unfinishedOpt.isPresent()) {
                log.info("检测到未完成的面试会话，返回现有会话: resumeId={}, sessionId={}",
                    request.resumeId(), unfinishedOpt.get().sessionId());
                return unfinishedOpt.get();
            }
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String skillId = request.skillId() != null ? request.skillId() : InterviewDefaults.SKILL_ID;
        String difficulty = request.difficulty() != null ? request.difficulty() : InterviewDefaults.DIFFICULTY;

        // 校验关联知识库归属当前用户（越权/不存在直接失败，避免静默丢弃用户选择）
        List<Long> knowledgeBaseIds = validateKnowledgeBases(request.knowledgeBaseIds());

        log.info("创建新面试会话: {}, skill: {}, difficulty: {}, questionCount: {}, resumeId: {}, kbIds: {}",
            sessionId, skillId, difficulty, request.questionCount(), request.resumeId(), knowledgeBaseIds);

        // Multi-Agent 编排路径：Planner 出大纲 + Interviewer/Critic 出首题，后续题目答题时动态生成
        boolean agentMode = agentProperties.isEnabled();
        List<InterviewQuestionDTO> questions = null;
        String planJson = null;
        int plannedTotal = request.questionCount();
        if (agentMode) {
            try {
                SkillDTO skill = resolveSkill(skillId, request);
                InterviewPlan plan = orchestrator.plan(new InterviewOrchestrator.PlanRequest(
                    sessionId, userId, request.llmProvider(), skill, difficulty,
                    request.questionCount(), request.resumeText(), knowledgeBaseIds));
                planJson = objectMapper.writeValueAsString(plan);

                GeneratedQuestion first = orchestrator.nextQuestion(
                    new InterviewOrchestrator.NextQuestionRequest(
                        sessionId, userId, request.llmProvider(), skillId, difficulty,
                        0, plannedTotal, plan, null, List.of(),
                        request.resumeId(), knowledgeBaseIds));
                questions = new ArrayList<>();
                questions.add(toAgentQuestion(first, 0, null));
            } catch (Exception e) {
                log.error("Agent 编排出题失败，降级为批量出题: sessionId={}", sessionId, e);
                agentMode = false;
                planJson = null;
            }
        }

        if (!agentMode) {
            // 获取历史问题（通用模式按 skillId 查询，有简历时按 resumeId + skillId 精确匹配）
            List<HistoricalQuestion> historicalQuestions =
                persistenceService.getHistoricalQuestions(skillId, request.resumeId());

            // 基于 Skill 生成面试问题（关联知识库时按 Skill 主题词 RAG 检索注入出题 prompt）
            questions = questionService.generateQuestionsBySkill(
                request.llmProvider(),
                skillId,
                difficulty,
                request.resumeText(),
                request.questionCount(),
                historicalQuestions,
                request.customCategories(),
                request.jdText(),
                knowledgeBaseIds
            );
            plannedTotal = questions.size();
        }

        // 保存到 Redis 缓存
        sessionCache.saveSession(
            sessionId,
            userId,
            request.resumeText() != null ? request.resumeText() : "",
            request.resumeId(),
            questions,
            0,
            SessionStatus.CREATED,
            plannedTotal,
            agentMode
        );

        // 保存到数据库
        try {
            persistenceService.saveSession(sessionId, request.resumeId(),
                plannedTotal, questions, request.llmProvider(), skillId, difficulty,
                knowledgeBaseIds, planJson);
        } catch (Exception e) {
            log.warn("保存面试会话到数据库失败: {}", e.getMessage(), e);
        }

        return new InterviewSessionDTO(
            sessionId,
            request.resumeText() != null ? request.resumeText() : "",
            plannedTotal,
            0,
            questions,
            SessionStatus.CREATED
        );
    }

    private SkillDTO resolveSkill(String skillId, CreateInterviewRequest request) {
        if (InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId)
                && request.customCategories() != null && !request.customCategories().isEmpty()) {
            return skillService.buildCustomSkill(request.customCategories(),
                request.jdText() != null ? request.jdText() : "");
        }
        return skillService.getSkill(skillId);
    }

    /** 编排产出转题目 DTO：追问挂到上一道主问题 */
    private InterviewQuestionDTO toAgentQuestion(GeneratedQuestion generated, int index,
                                                 Integer parentMainIndex) {
        Integer parentIndex = generated.isFollowUp() ? parentMainIndex : null;
        String category = generated.topicName() != null && !generated.topicName().isBlank()
            ? generated.topicName() : "Agent 出题";
        return InterviewQuestionDTO.create(index, generated.question(), AGENT_QUESTION_TYPE,
            generated.isFollowUp() ? category + "（追问）" : category,
            generated.topicName(), generated.isFollowUp(), parentIndex);
    }

    /**
     * 获取会话信息（优先从缓存获取，缓存未命中则从数据库恢复）
     */
    public InterviewSessionDTO getSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = getCurrentUserCachedSession(sessionId);
        if (cachedOpt.isPresent()) {
            return toDTO(cachedOpt.get());
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return toDTO(restoredSession);
    }

    /**
     * 查找并恢复未完成的面试会话
     */
    public Optional<InterviewSessionDTO> findUnfinishedSession(Long resumeId) {
        try {
            // 1. 先从 Redis 缓存查找
            Long userId = UserContext.requireUserId();
            Optional<String> cachedSessionIdOpt = sessionCache.findUnfinishedSessionId(userId, resumeId);
            if (cachedSessionIdOpt.isPresent()) {
                String sessionId = cachedSessionIdOpt.get();
                Optional<CachedSession> cachedOpt = getCurrentUserCachedSession(sessionId);
                if (cachedOpt.isPresent()) {
                    log.debug("从 Redis 缓存找到未完成会话: resumeId={}, sessionId={}", resumeId, sessionId);
                    return Optional.of(toDTO(cachedOpt.get()));
                }
            }

            // 2. 缓存未命中，从数据库查找
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findUnfinishedSession(resumeId);
            if (entityOpt.isEmpty()) {
                return Optional.empty();
            }

            InterviewSessionEntity entity = entityOpt.get();
            CachedSession restoredSession = restoreSessionFromEntity(entity);
            if (restoredSession != null) {
                return Optional.of(toDTO(restoredSession));
            }
        } catch (Exception e) {
            log.error("恢复未完成会话失败: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * 查找并恢复未完成的面试会话，如果不存在则抛出异常
     */
    public InterviewSessionDTO findUnfinishedSessionOrThrow(Long resumeId) {
        return findUnfinishedSession(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "未找到未完成的面试会话"));
    }

    /**
     * 从数据库恢复会话并缓存到 Redis
     */
    private CachedSession restoreSessionFromDatabase(String sessionId) {
        try {
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
            return entityOpt.map(this::restoreSessionFromEntity).orElse(null);
        } catch (Exception e) {
            log.error("从数据库恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从实体恢复会话并缓存到 Redis
     */
    private CachedSession restoreSessionFromEntity(InterviewSessionEntity entity) {
        try {
            // 解析问题列表
            List<InterviewQuestionDTO> questions = objectMapper.readValue(
                entity.getQuestionsJson(),
                new TypeReference<>() {}
            );

            // 恢复已保存的答案
            List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(entity.getSessionId());
            for (InterviewAnswerEntity answer : answers) {
                int index = answer.getQuestionIndex();
                if (index >= 0 && index < questions.size()) {
                    InterviewQuestionDTO question = questions.get(index);
                    questions.set(index, question.withAnswer(answer.getUserAnswer()));
                }
            }

            SessionStatus status = convertStatus(entity.getStatus());

            // 保存到 Redis 缓存
            int plannedTotal = entity.getTotalQuestions() != null && entity.getTotalQuestions() > 0
                ? entity.getTotalQuestions() : questions.size();
            sessionCache.saveSession(
                entity.getSessionId(),
                entity.getUserId(),
                entity.getResume() != null ? entity.getResume().getResumeText() : "",
                entity.getResume() != null ? entity.getResume().getId() : null,
                questions,
                entity.getCurrentQuestionIndex(),
                status,
                plannedTotal,
                entity.getInterviewPlanJson() != null
            );

            log.info("从数据库恢复会话到 Redis: sessionId={}, currentIndex={}, status={}",
                entity.getSessionId(), entity.getCurrentQuestionIndex(), entity.getStatus());

            // 返回缓存的会话
            return sessionCache.getSession(entity.getSessionId()).orElse(null);
        } catch (Exception e) {
            log.error("恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private SessionStatus convertStatus(InterviewSessionEntity.SessionStatus status) {
        return switch (status) {
            case CREATED -> SessionStatus.CREATED;
            case IN_PROGRESS -> SessionStatus.IN_PROGRESS;
            case COMPLETED -> SessionStatus.COMPLETED;
            case EVALUATED -> SessionStatus.EVALUATED;
        };
    }

    /**
     * 获取当前问题的响应（包含完成状态）
     */
    public Map<String, Object> getCurrentQuestionResponse(String sessionId) {
        InterviewQuestionDTO question = getCurrentQuestion(sessionId);
        if (question == null) {
            return Map.of(
                "completed", true,
                "message", "所有问题已回答完毕"
            );
        }
        return Map.of(
            "completed", false,
            "question", question
        );
    }

    /**
     * 获取当前问题
     */
    public InterviewQuestionDTO getCurrentQuestion(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        if (session.getCurrentIndex() >= questions.size()) {
            return null; // 所有问题已回答完
        }

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            session.setStatus(SessionStatus.IN_PROGRESS);
            sessionCache.updateSessionStatus(sessionId, SessionStatus.IN_PROGRESS);

            // 同步到数据库
            try {
                persistenceService.updateSessionStatus(sessionId,
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            } catch (Exception e) {
                log.warn("更新会话状态失败: {}", e.getMessage(), e);
            }
        }

        return questions.get(session.getCurrentIndex());
    }

    /**
     * 提交答案（并进入下一题）
     * 如果是最后一题，自动触发异步评估
     */
    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());
        // 状态守卫：已完成/已评估会话不接受再次提交，避免把 COMPLETED 会话打回 IN_PROGRESS
        // （前端自动暂存的在途 PUT / 重复 POST 竞态会真实触发）
        if (session.getStatus() == SessionStatus.COMPLETED
                || session.getStatus() == SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        // 更新问题答案
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);

        // 移动到下一题
        int newIndex = index + 1;
        int plannedTotal = session.resolvePlannedTotal(objectMapper);

        // 计算下一题：Agent 编排模式答题后动态出题（Interviewer→Critic→Reflexion 反思环），
        // 批量模式取预生成列表。Agent 动态出题失败/达计划题数上限时 nextQuestion=null，面试收尾进入评估。
        InterviewQuestionDTO nextQuestion = null;
        boolean questionsAppended = false;
        if (newIndex < questions.size()) {
            nextQuestion = questions.get(newIndex);
        } else if (session.isAgentMode() && newIndex < plannedTotal) {
            nextQuestion = generateNextAgentQuestion(
                request.sessionId(), session, questions, index, newIndex, plannedTotal, request.answer());
            if (nextQuestion != null) {
                questions.add(nextQuestion);
                questionsAppended = true;
            }
        }

        boolean hasNextQuestion = nextQuestion != null;
        SessionStatus newStatus = hasNextQuestion ? SessionStatus.IN_PROGRESS : SessionStatus.COMPLETED;

        // 先 DB 后 Redis（N4）：DB 是真相源，失败向前端抛可重试错误，不再吞掉，
        // 消灭「Redis 已 COMPLETED、DB 无记录、无评估任务」的缺口
        try {
            persistenceService.saveAnswer(
                request.sessionId(), index,
                question.question(), question.category(),
                request.answer(), 0, null  // 分数在报告生成时更新
            );
            // Agent 动态出题追加的新题落库，保证会话恢复与异步评估读到完整题目列表
            if (questionsAppended) {
                persistenceService.updateQuestions(request.sessionId(), questions);
            }
            persistenceService.updateCurrentQuestionIndex(request.sessionId(), newIndex);
            persistenceService.updateSessionStatus(request.sessionId(),
                newStatus == SessionStatus.COMPLETED
                    ? InterviewSessionEntity.SessionStatus.COMPLETED
                    : InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            if (!hasNextQuestion) {
                persistenceService.updateEvaluateStatus(request.sessionId(), AsyncTaskStatus.PENDING, null);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("保存答案到数据库失败: sessionId={}, questionIndex={}", request.sessionId(), index, e);
            throw new BusinessException(ErrorCode.INTERVIEW_ANSWER_SAVE_FAILED,
                "答案保存失败，请重新提交");
        }

        // DB 成功后再更新 Redis 缓存
        sessionCache.updateQuestions(request.sessionId(), questions);
        sessionCache.updateCurrentIndex(request.sessionId(), newIndex);
        if (newStatus == SessionStatus.COMPLETED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.COMPLETED);
        }

        // 评估任务入队独立 try：失败不影响提交结果，evaluate_status 已是 PENDING，补偿任务兜底重派
        if (!hasNextQuestion) {
            try {
                evaluateStreamProducer.sendEvaluateTask(request.sessionId());
                log.info("会话 {} 已完成所有问题，评估任务已入队", request.sessionId());
            } catch (Exception e) {
                log.error("评估任务入队失败，evaluate_status=PENDING 留待补偿任务重派: sessionId={}",
                    request.sessionId(), e);
            }
            // Agent 编排：记录 EVALUATING 状态转移轨迹（评估本体委托统一评估管线异步执行）
            if (session.isAgentMode()) {
                orchestrator.recordEvaluationEnqueued(request.sessionId(), session.getUserId());
            }
        }

        log.info("会话 {} 提交答案: 问题{}, 已答 {}/{}",
            request.sessionId(), index, newIndex, plannedTotal);

        return new SubmitAnswerResponse(
            hasNextQuestion,
            nextQuestion,
            newIndex,
            plannedTotal
        );
    }

    /**
     * Agent 编排模式：答题后动态生成下一题（Interviewer→Critic→Reflexion）。
     * 从会话实体读大纲/技能/知识库上下文，交编排器出题；任何失败返回 null（面试收尾进入评估，不阻断）。
     */
    private InterviewQuestionDTO generateNextAgentQuestion(String sessionId, CachedSession session,
            List<InterviewQuestionDTO> questions, int answeredIndex, int newIndex,
            int plannedTotal, String lastAnswer) {
        try {
            InterviewSessionEntity entity = persistenceService.findBySessionId(sessionId).orElse(null);
            if (entity == null) {
                log.warn("Agent 动态出题：会话实体缺失，收尾面试: sessionId={}", sessionId);
                return null;
            }
            InterviewPlan plan = parsePlanQuietly(entity.getInterviewPlanJson());
            List<Long> knowledgeBaseIds = parseKnowledgeBaseIdsQuietly(entity.getKnowledgeBaseIdsJson());
            List<String> askedQuestions = questions.stream()
                .map(InterviewQuestionDTO::question)
                .toList();
            String provider = "default".equals(entity.getLlmProvider()) ? null : entity.getLlmProvider();

            GeneratedQuestion generated = orchestrator.nextQuestion(
                new InterviewOrchestrator.NextQuestionRequest(
                    sessionId, session.getUserId(), provider,
                    entity.getSkillId(), entity.getDifficulty(),
                    newIndex, plannedTotal, plan, lastAnswer, askedQuestions,
                    entity.getResumeId(), knowledgeBaseIds));
            Integer parentMainIndex = resolveParentMainIndex(questions, answeredIndex);
            return toAgentQuestion(generated, newIndex, parentMainIndex);
        } catch (Exception e) {
            log.error("Agent 动态出题失败，提前收尾面试: sessionId={}, newIndex={}", sessionId, newIndex, e);
            return null;
        }
    }

    /** 追问归属到最近一道主问题（沿题目链上溯，跳过追问）。 */
    private Integer resolveParentMainIndex(List<InterviewQuestionDTO> questions, int answeredIndex) {
        for (int i = Math.min(answeredIndex, questions.size() - 1); i >= 0; i--) {
            if (!questions.get(i).isFollowUp()) {
                return i;
            }
        }
        return answeredIndex;
    }

    private InterviewPlan parsePlanQuietly(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(planJson, InterviewPlan.class);
        } catch (Exception e) {
            log.warn("解析面试大纲 JSON 失败，Agent 出题降级无大纲: {}", e.getMessage());
            return null;
        }
    }

    private List<Long> parseKnowledgeBaseIdsQuietly(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("解析知识库 ID JSON 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 暂存答案（不进入下一题）
     */
    public void saveAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());
        // 状态守卫：已完成/已评估会话不接受暂存（防止在途暂存 PUT 把会话状态回退到 IN_PROGRESS）
        if (session.getStatus() == SessionStatus.COMPLETED
                || session.getStatus() == SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        // 更新问题答案
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);

        // 更新 Redis 缓存
        sessionCache.updateQuestions(request.sessionId(), questions);

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.IN_PROGRESS);
        }

        // 保存答案到数据库（不更新currentIndex）
        try {
            persistenceService.saveAnswer(
                request.sessionId(), index,
                question.question(), question.category(),
                request.answer(), 0, null
            );
            persistenceService.updateSessionStatus(request.sessionId(),
                InterviewSessionEntity.SessionStatus.IN_PROGRESS);
        } catch (Exception e) {
            log.warn("暂存答案到数据库失败: {}", e.getMessage(), e);
        }

        log.info("会话 {} 暂存答案: 问题{}", request.sessionId(), index);
    }

    /**
     * 提前交卷（触发异步评估）
     */
    public void completeInterview(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() == SessionStatus.COMPLETED || session.getStatus() == SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }

        // 先 DB 后 Redis（与 submitAnswer 对称，N8）：DB 失败抛可重试错误，评估任务只在 DB 成功后入队
        try {
            persistenceService.updateSessionStatus(sessionId,
                InterviewSessionEntity.SessionStatus.COMPLETED);
            persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("提前交卷更新会话状态失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.INTERVIEW_ANSWER_SAVE_FAILED,
                "交卷失败，请重试");
        }

        sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);

        // 评估任务入队独立 try：失败靠 evaluate_status=PENDING + 补偿任务兜底
        try {
            evaluateStreamProducer.sendEvaluateTask(sessionId);
            log.info("会话 {} 提前交卷，评估任务已入队", sessionId);
        } catch (Exception e) {
            log.error("评估任务入队失败，evaluate_status=PENDING 留待补偿任务重派: sessionId={}", sessionId, e);
        }
        // Agent 编排：记录 EVALUATING 状态转移轨迹（与 submitAnswer 收尾对称）
        if (session.isAgentMode()) {
            orchestrator.recordEvaluationEnqueued(sessionId, session.getUserId());
        }
    }

    /**
     * 获取或恢复会话（优先从缓存获取）
     */
    private CachedSession getOrRestoreSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = getCurrentUserCachedSession(sessionId);
        if (cachedOpt.isPresent()) {
            // 刷新 TTL
            sessionCache.refreshSessionTTL(sessionId);
            return cachedOpt.get();
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return restoredSession;
    }

    /**
     * 生成评估报告
     */
    public InterviewReportDTO generateReport(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() != SessionStatus.COMPLETED && session.getStatus() != SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, "面试尚未完成，无法生成报告");
        }

        log.info("生成面试报告: {}", sessionId);

        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        // 获取 LLM 客户端
        String provider = null;
        Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
        if (entityOpt.isPresent()) {
            provider = entityOpt.get().getLlmProvider();
        }
        ChatModel chatModel = llmProviderRegistry.getChatModelOrDefault(provider);

        InterviewReportDTO report = evaluationService.evaluateInterview(
            chatModel,
            sessionId,
            session.getResumeText(),
            questions
        );

        // 更新 Redis 缓存状态
        sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);

        // 保存报告到数据库
        try {
            persistenceService.saveReport(sessionId, report);
        } catch (Exception e) {
            log.warn("保存报告到数据库失败: {}", e.getMessage(), e);
        }

        return report;
    }

    public void deleteSession(String sessionId) {
        getOrRestoreSession(sessionId);
        persistenceService.deleteSessionBySessionId(sessionId);
        sessionCache.deleteSession(sessionId);
    }

    /**
     * 获取会话的 Multi-Agent 决策轨迹（按题号分组，PLANNING 阶段 questionIndex=null）。
     * 供前端「Agent 决策透明化」面板回放 Planner→Interviewer→Critic→Reflexion。
     */
    public List<AgentTraceGroupDTO> getAgentTrace(String sessionId) {
        Long userId = UserContext.requireUserId();
        List<AgentRunStepEntity> steps = agentTraceService.listBySession(sessionId, userId);
        Map<Integer, List<AgentTraceGroupDTO.AgentTraceStepDTO>> grouped = new LinkedHashMap<>();
        for (AgentRunStepEntity step : steps) {
            grouped.computeIfAbsent(step.getQuestionIndex(), k -> new ArrayList<>())
                .add(new AgentTraceGroupDTO.AgentTraceStepDTO(
                    step.getStepOrder() == null ? 0 : step.getStepOrder(),
                    step.getRole(), step.getAction(),
                    step.getActionInput(), step.getObservation()));
        }
        return grouped.entrySet().stream()
            .map(e -> new AgentTraceGroupDTO(e.getKey(), e.getValue()))
            .toList();
    }

    /**
     * 获取会话的面试大纲与进度（前端侧栏大纲进度条）。批量会话返回 agentMode=false、plan=null。
     */
    public AgentPlanProgressDTO getAgentPlan(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);
        InterviewPlan plan = persistenceService.findBySessionId(sessionId)
            .map(entity -> parsePlanQuietly(entity.getInterviewPlanJson()))
            .orElse(null);
        return new AgentPlanProgressDTO(
            session.isAgentMode(),
            session.getCurrentIndex(),
            session.resolvePlannedTotal(objectMapper),
            plan);
    }

    /**
     * 获取当前用户的候选人画像（按 topic 聚合的历史薄弱点/掌握点），skillId 可空表示全方向。
     */
    public List<CandidateMemoryProfileDTO> getCandidateProfile(String skillId) {
        Long userId = UserContext.requireUserId();
        return candidateMemoryService.getProfile(userId, skillId);
    }

    /**
     * 将缓存会话转换为 DTO
     */
    private InterviewSessionDTO toDTO(CachedSession session) {
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
        // Agent 编排模式题目动态生成，进度以计划总题数为准（否则进度条随已生成题数跳变）
        int totalQuestions = session.resolvePlannedTotal(objectMapper);
        return new InterviewSessionDTO(
            session.getSessionId(),
            session.getResumeText(),
            totalQuestions,
            session.getCurrentIndex(),
            questions,
            session.getStatus()
        );
    }

    private Optional<CachedSession> getCurrentUserCachedSession(String sessionId) {
        Long userId = UserContext.requireUserId();
        return sessionCache.getSession(sessionId)
            .filter(session -> userId.equals(session.getUserId()));
    }
}
