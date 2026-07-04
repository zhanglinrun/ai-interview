package com.linrun.interview.common.async;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.modules.interview.mapper.InterviewSessionMapper;
import com.linrun.interview.modules.interview.model.InterviewSessionEntity;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * RocketMQ 事务消息本地事务监听器（app.async.engine=rocketmq 时启用）。
 *
 * <p>面试/语音评估管道以事务消息投递：half 消息发出后，broker 回调本地事务确认状态。
 * 本项目采用 DB-first——调用方（{@code submitAnswer / completeInterview}）在发送前已把
 * {@code evaluate_status=PENDING} 写入 DB，故：
 * <ul>
 *   <li>{@link #executeLocalTransaction}：按 sessionId 查 DB，已写入评估状态 → {@code COMMIT}，
 *       否则 {@code UNKNOWN}（交回查兜底，覆盖发送与 DB 提交之间的极小窗口）</li>
 *   <li>{@link #checkLocalTransaction}：broker 超时回查，再查一次 DB，
 *       已写入 → {@code COMMIT}，仍未写入（本地事务确已失败）→ {@code ROLLBACK} 丢弃 half 消息</li>
 * </ul>
 * 这样「面试完成 → 评估任务投递」与「DB 状态变更」原子化，配合消费端幂等 + 补偿任务双兜底。
 * 非面试评估的消息（缺少 sessionId 字段，如语音/简历）默认 {@code COMMIT}。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.async.engine", havingValue = AsyncEngineProperties.ENGINE_ROCKETMQ,
    matchIfMissing = true)
@RocketMQTransactionListener
public class AsyncTaskTransactionListener implements RocketMQLocalTransactionListener {

    private final InterviewSessionMapper interviewSessionMapper;
    private final ObjectMapper objectMapper;

    public AsyncTaskTransactionListener(InterviewSessionMapper interviewSessionMapper,
                                        ObjectMapper objectMapper) {
        this.interviewSessionMapper = interviewSessionMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        String sessionId = extractSessionId(msg, arg);
        if (sessionId == null) {
            return RocketMQLocalTransactionState.COMMIT;
        }
        boolean ready = interviewReadyForEvaluation(sessionId);
        log.info("事务消息本地事务确认: sessionId={}, ready={}", sessionId, ready);
        return ready ? RocketMQLocalTransactionState.COMMIT : RocketMQLocalTransactionState.UNKNOWN;
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        String sessionId = extractSessionId(msg, null);
        if (sessionId == null) {
            return RocketMQLocalTransactionState.COMMIT;
        }
        boolean ready = interviewReadyForEvaluation(sessionId);
        log.info("事务消息回查: sessionId={}, ready={}", sessionId, ready);
        return ready ? RocketMQLocalTransactionState.COMMIT : RocketMQLocalTransactionState.ROLLBACK;
    }

    /** DB-first 写入的 evaluate_status 即本地事务凭据：非空说明本地事务已提交。 */
    private boolean interviewReadyForEvaluation(String sessionId) {
        return EntityQueries
            .selectOne(interviewSessionMapper, InterviewSessionEntity::getSessionId, sessionId)
            .map(session -> session.getEvaluateStatus() != null)
            .orElse(false);
    }

    @SuppressWarnings("unchecked")
    private String extractSessionId(Message msg, Object arg) {
        if (arg instanceof Map<?, ?> argMap) {
            Object sid = argMap.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
            if (sid != null) {
                return sid.toString();
            }
        }
        return parsePayload(msg).get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
    }

    private Map<String, String> parsePayload(Message msg) {
        if (msg == null) {
            return Collections.emptyMap();
        }
        Object payload = msg.getPayload();
        byte[] bytes = payload instanceof byte[] pb
            ? pb : String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        try {
            return objectMapper.readValue(bytes, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("事务消息体解析失败，按无 sessionId 处理: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
