package com.linrun.interview.modules.interview.listener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.async.AbstractStreamConsumer;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.infrastructure.redis.InterviewSessionCache;
import com.linrun.interview.infrastructure.redis.RedisService;
import com.linrun.interview.modules.interview.mapper.InterviewSessionMapper;
import com.linrun.interview.modules.interview.memory.CandidateMemoryService;
import com.linrun.interview.modules.interview.model.InterviewAnswerEntity;
import com.linrun.interview.modules.interview.model.InterviewQuestionDTO;
import com.linrun.interview.modules.interview.model.InterviewReportDTO;
import com.linrun.interview.modules.interview.model.InterviewSessionEntity;
import com.linrun.interview.modules.interview.model.InterviewSessionDTO.SessionStatus;
import com.linrun.interview.modules.interview.service.AnswerEvaluationService;
import com.linrun.interview.modules.interview.service.InterviewPersistenceService;
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

    public EvaluateStreamConsumer(
        RedisService redisService,
        InterviewSessionMapper interviewSessionMapper,
        AnswerEvaluationService evaluationService,
        InterviewPersistenceService persistenceService,
        ObjectMapper objectMapper,
        LlmProviderRegistry llmProviderRegistry,
        CandidateMemoryService candidateMemoryService,
        InterviewSessionCache sessionCache
    ) {
        super(redisService);
        this.sessionRepository = interviewSessionMapper;
        this.evaluationService = evaluationService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.llmProviderRegistry = llmProviderRegistry;
        this.candidateMemoryService = candidateMemoryService;
        this.sessionCache = sessionCache;
    }

    record EvaluatePayload(String sessionId) {}

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
            InterviewReportDTO storedReport = persistenceService.loadStoredReportInternal(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
                    "会话已标记评估完成，但持久化报告不完整"));
            candidateMemoryService.extractAndSave(session, storedReport, questions);
            sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);
            log.info("已从持久化报告恢复能力观测: sessionId={}", sessionId);
            return;
        }

        List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(sessionId);
        for (InterviewAnswerEntity answer : answers) {
            int index = answer.getQuestionIndex();
            if (index >= 0 && index < questions.size()) {
                InterviewQuestionDTO question = questions.get(index);
                questions.set(index, question.withAnswer(answer.getUserAnswer()));
            }
        }

        // 异步评估无 UserContext：从会话实体恢复 userId，走该用户的 BYOK「我的模型」
        ChatModel chatModel = llmProviderRegistry.getUserChatModel(session.getUserId());

        String resumeText = session.getResume() != null ? session.getResume().getResumeText() : "";
        InterviewReportDTO report = evaluationService.evaluateInterview(chatModel, sessionId, resumeText, questions);
        persistenceService.saveReport(sessionId, report);
        sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);
        // 能力观测是报告的确定性派生数据；失败触发 MQ 重试，重投时直接走上面的恢复分支。
        candidateMemoryService.extractAndSave(session, report, questions);
    }

    @Override
    protected void markCompleted(EvaluatePayload payload) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(EvaluatePayload payload, String error) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.FAILED, error);
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
