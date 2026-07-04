package com.linrun.interview.modules.interview.listener;

import com.linrun.interview.common.async.AbstractStreamConsumer;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.infrastructure.redis.RedisService;
import com.linrun.interview.modules.interview.model.InterviewAnswerEntity;
import com.linrun.interview.modules.interview.model.InterviewQuestionDTO;
import com.linrun.interview.modules.interview.model.InterviewReportDTO;
import com.linrun.interview.modules.interview.service.InterviewPersistenceService;
import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.modules.resume.mapper.ResumeEntityMapper;
import com.linrun.interview.modules.resume.model.ResumeEntity;
import com.linrun.interview.modules.interview.model.InterviewSessionEntity;
import com.linrun.interview.modules.interview.memory.CandidateMemoryService;
import com.linrun.interview.modules.interview.service.AnswerEvaluationService;
import com.linrun.interview.modules.interview.mapper.InterviewSessionMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.StreamMessageId;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    public EvaluateStreamConsumer(
        RedisService redisService,
        InterviewSessionMapper interviewSessionMapper,
        AnswerEvaluationService evaluationService,
        InterviewPersistenceService persistenceService,
        ObjectMapper objectMapper,
        LlmProviderRegistry llmProviderRegistry,
        CandidateMemoryService candidateMemoryService
    ) {
        super(redisService);
        this.sessionRepository = interviewSessionMapper;
        this.evaluationService = evaluationService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.llmProviderRegistry = llmProviderRegistry;
        this.candidateMemoryService = candidateMemoryService;
    }

    record EvaluatePayload(String sessionId) {}

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "evaluate-consumer";
    }

    @Override
    protected EvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
        if (sessionId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new EvaluatePayload(sessionId);
    }

    @Override
    protected String payloadIdentifier(EvaluatePayload payload) {
        return "sessionId=" + payload.sessionId();
    }

    @Override
    protected void markProcessing(EvaluatePayload payload) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(EvaluatePayload payload) {
        String sessionId = payload.sessionId();
        Optional<InterviewSessionEntity> sessionOpt = persistenceService.findBySessionIdInternal(sessionId);
        if (sessionOpt.isEmpty()) {
            log.warn("会话已被删除，跳过评估任务: sessionId={}", sessionId);
            return;
        }

        InterviewSessionEntity session = sessionOpt.get();
        // 幂等：已评估的会话直接跳过（补偿任务重派可能与已完成的评估重复）
        if (session.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED
            || session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED) {
            log.info("会话已评估，跳过重复评估任务: sessionId={}", sessionId);
            return;
        }
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

        List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(sessionId);
        for (InterviewAnswerEntity answer : answers) {
            int index = answer.getQuestionIndex();
            if (index >= 0 && index < questions.size()) {
                InterviewQuestionDTO question = questions.get(index);
                questions.set(index, question.withAnswer(answer.getUserAnswer()));
            }
        }

        // 获取 LLM 客户端
        String provider = session.getLlmProvider();
        ChatModel chatModel = llmProviderRegistry.getChatModelOrDefault(provider);

        String resumeText = session.getResume() != null ? session.getResume().getResumeText() : "";
        InterviewReportDTO report = evaluationService.evaluateInterview(chatModel, sessionId, resumeText, questions);
        persistenceService.saveReport(sessionId, report);
        // 跨会话候选人画像：从评估报告 LLM 抽取薄弱点/掌握点入库（Planner 下次面试注入），
        // 失败静默不阻断评估主链路；本消费者已幂等（EVALUATED 跳过），不会重复抽取
        candidateMemoryService.extractAndSaveQuietly(session, report);
    }

    @Override
    protected void markCompleted(EvaluatePayload payload) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(EvaluatePayload payload, String error) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.FAILED, error);
    }

    @Override
    protected Map<String, String> buildRetryMessage(EvaluatePayload payload, int retryCount) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_SESSION_ID, payload.sessionId(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
        );
    }

    /**
     * 更新评估状态
     */
    private void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        try {
            EntityQueries.selectOne(sessionRepository, InterviewSessionEntity::getSessionId, sessionId)
                .ifPresent(session -> {
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
