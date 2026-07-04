package com.linrun.interview.modules.interview.listener;

import com.linrun.interview.common.async.AbstractStreamProducer;
import com.linrun.interview.common.async.TaskQueueChannel;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.modules.interview.mapper.InterviewSessionMapper;
import com.linrun.interview.modules.interview.model.InterviewSessionEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 面试评估任务生产者
 * 负责发送评估任务到异步任务管道
 */
@Slf4j
@Component
public class EvaluateStreamProducer extends AbstractStreamProducer<String> {

    private final InterviewSessionMapper sessionRepository;

    public EvaluateStreamProducer(TaskQueueChannel taskQueueChannel,
                                  InterviewSessionMapper interviewSessionMapper) {
        super(taskQueueChannel);
        this.sessionRepository = interviewSessionMapper;
    }

    /**
     * 发送评估任务（事务消息语义：RocketMQ 引擎下 half 消息 + 本地事务确认，
     * Redis Stream 引擎退化为普通入队，DB-first + 补偿任务兜底）。
     *
     * @param sessionId 面试会话ID
     */
    public void sendEvaluateTask(String sessionId) {
        sendTaskInTransaction(sessionId);
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
    protected Map<String, String> buildMessage(String sessionId) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId,
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(String sessionId) {
        return "sessionId=" + sessionId;
    }

    @Override
    protected void onSendFailed(String sessionId, String error) {
        // 入队失败标记 PENDING（非 FAILED）：由 InterviewEvaluationCompensationJob 定时重派
        log.error("评估任务入队失败，标记 PENDING 留待补偿任务重派: sessionId={}, error={}", sessionId, error);
        updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, truncateError(error));
    }

    /**
     * 更新评估状态
     */
    private void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        EntityQueries.selectOne(sessionRepository, InterviewSessionEntity::getSessionId, sessionId)
            .ifPresent(session -> {
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            MapperUtils.save(sessionRepository, session);
        });
    }
}
