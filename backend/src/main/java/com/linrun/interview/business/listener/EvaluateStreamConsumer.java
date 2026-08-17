package com.linrun.interview.business.listener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.infra.messaging.AbstractStreamConsumer;
import com.linrun.interview.infra.messaging.AsyncTaskStreamConstants;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.infra.persistence.EntityQueries;
import com.linrun.interview.infra.persistence.MapperUtils;
import com.linrun.interview.infra.redis.InterviewSessionCache;
import com.linrun.interview.infra.redis.RedisService;
import com.linrun.interview.business.mapper.InterviewSessionMapper;
import com.linrun.interview.business.service.CandidateMemoryService;
import com.linrun.interview.business.entity.InterviewAnswerEntity;
import com.linrun.interview.business.vo.InterviewQuestionDTO;
import com.linrun.interview.business.vo.InterviewReportDTO;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.vo.InterviewSessionDTO.SessionStatus;
import com.linrun.interview.business.service.AnswerEvaluationService;
import com.linrun.interview.business.service.InterviewOrchestrator;
import com.linrun.interview.business.service.InterviewPersistenceService;
import com.linrun.interview.infra.observability.LlmUsageContext;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 面试评估 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行评估
 */
@Slf4j
@Component
public class EvaluateStreamConsumer extends AbstractStreamConsumer<EvaluateStreamConsumer.EvaluatePayload> {

    private final InterviewSessionMapper sessionRepository;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final LlmProviderRegistry llmProviderRegistry;
    private final CandidateMemoryService candidateMemoryService;
    private final InterviewSessionCache sessionCache;
    private final InterviewOrchestrator orchestrator;

    public EvaluateStreamConsumer(
        RedisService redisService,
        InterviewSessionMapper interviewSessionMapper,
        AnswerEvaluationService evaluationService,
        InterviewPersistenceService persistenceService,
        ObjectMapper objectMapper,
        LlmProviderRegistry llmProviderRegistry,
        CandidateMemoryService candidateMemoryService,
        InterviewSessionCache sessionCache,
        InterviewOrchestrator orchestrator
    ) {
        super(redisService);
        this.sessionRepository = interviewSessionMapper;
        this.evaluationService = evaluationService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.llmProviderRegistry = llmProviderRegistry;
        this.candidateMemoryService = candidateMemoryService;
        this.sessionCache = sessionCache;
        this.orchestrator = orchestrator;
    }

    record EvaluatePayload(String sessionId, Long userId) {}

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME;
    }

    @Override
    protected EvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
        String userId = data.get(AsyncTaskStreamConstants.FIELD_USER_ID);
        if (sessionId == null || userId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        try {
            return new EvaluatePayload(sessionId, Long.parseLong(userId));
        } catch (NumberFormatException e) {
            log.warn("消息用户 ID 格式错误，跳过: messageId={}", messageId);
            return null;
        }
    }

    @Override
    protected String payloadIdentifier(EvaluatePayload payload) {
        return "sessionId=" + payload.sessionId() + ", userId=" + payload.userId();
    }

    @Override
    protected boolean shouldSkip(EvaluatePayload payload) {
        Optional<InterviewSessionEntity> session = persistenceService.findBySessionIdInternal(
            payload.sessionId());
        if (session.isEmpty() || !payload.userId().equals(session.get().getUserId())) {
            return true;
        }
        if (session.get().getEvaluateStatus() != AsyncTaskStatus.COMPLETED) {
            return false;
        }
        return persistenceService.loadStoredReportInternal(payload.sessionId()).isPresent();
    }

    @Override
    protected void markProcessing(EvaluatePayload payload) {
        updateEvaluateStatus(payload, AsyncTaskStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(EvaluatePayload payload) {
        String sessionId = payload.sessionId();
        Optional<InterviewSessionEntity> sessionOpt = persistenceService.findBySessionIdInternal(sessionId);
        if (sessionOpt.isEmpty() || !payload.userId().equals(sessionOpt.get().getUserId())) {
            log.warn("会话已被删除，跳过评估任务: sessionId={}", sessionId);
            return;
        }

        InterviewSessionEntity session = sessionOpt.get();
        List<InterviewQuestionDTO> questions;
        try {
            questions = objectMapper.readValue(
                session.getQuestionsJson(),
                new TypeReference<>() {}
            );
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("解析面试题目 JSON 失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试题目数据损坏，无法评估: " + e.getMessage(), e);
        }

        // 报告与能力观测跨两个本地事务：若进程在二者之间退出，broker 会重投。
        // 此处从数据库报告重建观测，不重新调用 LLM；唯一键保证部分成功后的重复写入安全。
        if (session.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED) {
            Optional<InterviewReportDTO> storedReport =
                persistenceService.loadStoredReportInternal(sessionId);
            if (storedReport.isPresent()) {
                candidateMemoryService.extractAndSave(session, storedReport.get(), questions);
                sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);
                log.info("已从持久化报告恢复能力观测: sessionId={}", sessionId);
                return;
            }
            log.info("已评估会话缺少有效报告，重新评估: sessionId={}", sessionId);
        }

        List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(sessionId);
        for (InterviewAnswerEntity answer : answers) {
            int index = answer.getQuestionIndex();
            if (index >= 0 && index < questions.size()) {
                InterviewQuestionDTO question = questions.get(index);
                questions.set(index, question.withAnswer(answer.getUserAnswer()));
            }
        }

        // userId 来自持久化消息并与实体交叉校验，异步线程不读取 UserContext。
        ChatModel chatModel = llmProviderRegistry.getUserChatModel(payload.userId());

        String resumeText = session.getResume() != null ? session.getResume().getResumeText() : "";
        long startedNanos = System.nanoTime();
        InterviewReportDTO report;
        try (LlmUsageContext.Scope ignored = orchestrator.openEvaluatingUsage(sessionId, payload.userId())) {
            report = evaluationService.evaluateInterview(chatModel, sessionId, resumeText, questions);
        } catch (RuntimeException e) {
            orchestrator.recordEvaluationFailed(sessionId, payload.userId(),
                (System.nanoTime() - startedNanos) / 1_000_000L, e.getMessage(), false);
            throw e;
        }
        persistenceService.saveReport(sessionId, report);
        sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);
        orchestrator.recordEvaluationCompleted(sessionId, payload.userId(),
            (System.nanoTime() - startedNanos) / 1_000_000L,
            "score=" + report.overallScore());
        // 能力观测是报告的确定性派生数据；失败触发 MQ 重试，重投时直接走上面的恢复分支。
        candidateMemoryService.extractAndSave(session, report, questions);
    }

    @Override
    protected void markCompleted(EvaluatePayload payload) {
        updateEvaluateStatus(payload, AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(EvaluatePayload payload, String error) {
        updateEvaluateStatus(payload, AsyncTaskStatus.FAILED, error);
        orchestrator.recordEvaluationFailed(payload.sessionId(), payload.userId(), 0L, error, true);
    }

    /**
     * 更新评估状态
     */
    private void updateEvaluateStatus(EvaluatePayload payload, AsyncTaskStatus status, String error) {
        String sessionId = payload.sessionId();
        try {
            EntityQueries.selectOne(sessionRepository, InterviewSessionEntity::getSessionId, sessionId)
                .ifPresent(session -> {
                if (!payload.userId().equals(session.getUserId())) {
                    log.warn("拒绝更新其他用户的面试评估状态: sessionId={}, messageUserId={}",
                        sessionId, payload.userId());
                    return;
                }
                session.setEvaluateStatus(status);
                session.setEvaluateError(error);
                MapperUtils.save(sessionRepository, session);
                log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
            });
        } catch (Exception e) {
            log.error("更新评估状态失败: sessionId={}, status={}, error={}", sessionId, status, e.getMessage(), e);
        }
    }

}
