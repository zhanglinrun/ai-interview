package com.linrun.interview.business.listener;

import com.linrun.interview.infra.messaging.AbstractStreamProducer;
import com.linrun.interview.infra.messaging.TaskQueueChannel;
import com.linrun.interview.infra.messaging.AsyncTaskStreamConstants;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.infra.persistence.EntityQueries;
import com.linrun.interview.infra.persistence.MapperUtils;
import com.linrun.interview.business.mapper.InterviewSessionMapper;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 面试评估任务生产者
 * 负责发送评估任务到异步任务管道
 */
@Slf4j
@Component
public class EvaluateStreamProducer
    extends AbstractStreamProducer<EvaluateStreamProducer.EvaluateTaskPayload> {

    private final InterviewSessionMapper sessionRepository;

    record EvaluateTaskPayload(String sessionId, Long userId, String commandId, String agentRunId) {
    }

    public EvaluateStreamProducer(TaskQueueChannel taskQueueChannel,
                                  InterviewSessionMapper interviewSessionMapper) {
        super(taskQueueChannel);
        this.sessionRepository = interviewSessionMapper;
    }

    /** 发送评估任务；业务已采用 DB-first，并由补偿任务兜底投递失败。 */
    public void sendEvaluateTask(String sessionId) {
        sendEvaluateTask(sessionId, null, null);
    }

    public void sendEvaluateTask(String sessionId, String commandId, String agentRunId) {
        InterviewSessionEntity session = EntityQueries.selectOne(
            sessionRepository, InterviewSessionEntity::getSessionId, sessionId)
            .orElseThrow(() -> new com.linrun.interview.common.exception.BusinessException(
                com.linrun.interview.common.exception.ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        sendTask(new EvaluateTaskPayload(sessionId, session.getUserId(), commandId, agentRunId));
    }

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(EvaluateTaskPayload payload) {
        Map<String, String> message = new java.util.HashMap<>();
        message.put(AsyncTaskStreamConstants.FIELD_SESSION_ID, payload.sessionId());
        message.put(AsyncTaskStreamConstants.FIELD_USER_ID, payload.userId().toString());
        message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
        if (payload.commandId() != null) {
            message.put(AsyncTaskStreamConstants.FIELD_COMMAND_ID, payload.commandId());
        }
        if (payload.agentRunId() != null) {
            message.put(AsyncTaskStreamConstants.FIELD_AGENT_RUN_ID, payload.agentRunId());
        }
        return message;
    }

    @Override
    protected String payloadIdentifier(EvaluateTaskPayload payload) {
        return "sessionId=" + payload.sessionId() + ", userId=" + payload.userId();
    }

    @Override
    protected void onSendFailed(EvaluateTaskPayload payload, String error) {
        // 入队失败标记 PENDING（非 FAILED）：由 InterviewEvaluationCompensationJob 定时重派
        log.error("评估任务入队失败，标记 PENDING 留待补偿任务重派: sessionId={}, error={}",
            payload.sessionId(), error);
        updateEvaluateStatus(payload, AsyncTaskStatus.PENDING, truncateError(error));
    }

    /**
     * 更新评估状态
     */
    private void updateEvaluateStatus(EvaluateTaskPayload payload, AsyncTaskStatus status, String error) {
        EntityQueries.selectOne(sessionRepository, InterviewSessionEntity::getSessionId, payload.sessionId())
            .ifPresent(session -> {
            if (!payload.userId().equals(session.getUserId())) {
                log.warn("拒绝更新其他用户的面试评估状态: sessionId={}, messageUserId={}",
                    payload.sessionId(), payload.userId());
                return;
            }
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            MapperUtils.save(sessionRepository, session);
        });
    }
}
