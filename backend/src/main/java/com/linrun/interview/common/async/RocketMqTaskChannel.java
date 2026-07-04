package com.linrun.interview.common.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RocketMQ 投递通道（app.async.engine=rocketmq 时启用）。
 *
 * <p>把 {@link TaskQueueChannel} 的逻辑管道键（Redis Stream key）映射为 RocketMQ topic，
 * 消息体以 JSON 承载与 Redis Stream 完全一致的字段（含 taskId / retryCount），
 * 消费侧解析后复用同一套业务与幂等逻辑，实现「一行配置切换引擎」。
 *
 * <ul>
 *   <li>{@link #send} 普通同步发送（简历分析管道）</li>
 *   <li>{@link #sendInTransaction} 事务消息（面试/语音评估管道）：half 消息 → 本地事务回查
 *       （见 {@link AsyncTaskTransactionListener}）→ commit，保证「DB 状态变更」与「消息投递」原子性</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.async.engine", havingValue = AsyncEngineProperties.ENGINE_ROCKETMQ,
    matchIfMissing = true)
public class RocketMqTaskChannel implements TaskQueueChannel {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /** 逻辑管道键 → RocketMQ topic 映射。 */
    private static final Map<String, String> STREAM_KEY_TO_TOPIC = Map.of(
        AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY, AsyncTaskStreamConstants.RESUME_ANALYZE_TOPIC,
        AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY, AsyncTaskStreamConstants.INTERVIEW_EVALUATE_TOPIC,
        AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY, AsyncTaskStreamConstants.VOICE_EVALUATE_TOPIC
    );

    public RocketMqTaskChannel(RocketMQTemplate rocketMQTemplate, ObjectMapper objectMapper) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
        log.info("异步任务引擎: RocketMQ（TaskQueueChannel=RocketMqTaskChannel）");
    }

    @Override
    public String send(String streamKey, Map<String, String> message) {
        String topic = resolveTopic(streamKey);
        Message<String> mqMessage = buildMessage(message);
        SendResult result = rocketMQTemplate.syncSend(topic, mqMessage);
        return result.getMsgId();
    }

    @Override
    public String sendInTransaction(String streamKey, Map<String, String> message) {
        String topic = resolveTopic(streamKey);
        Message<String> mqMessage = buildMessage(message);
        // arg 透传消息字段，供本地事务执行/回查判定 DB 状态（见 AsyncTaskTransactionListener）
        TransactionSendResult result =
            rocketMQTemplate.sendMessageInTransaction(topic, mqMessage, message);
        log.info("RocketMQ 事务消息已发送: topic={}, localTxState={}, msgId={}",
            topic, result.getLocalTransactionState(), result.getMsgId());
        return result.getMsgId();
    }

    private Message<String> buildMessage(Map<String, String> message) {
        String taskId = message.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
        MessageBuilder<String> builder = MessageBuilder.withPayload(toJson(message));
        if (taskId != null) {
            // KEYS 便于在 RocketMQ 控制台按 taskId 检索消息
            builder.setHeader("KEYS", taskId);
        }
        return builder.build();
    }

    private String resolveTopic(String streamKey) {
        String topic = STREAM_KEY_TO_TOPIC.get(streamKey);
        if (topic == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "未知的异步管道 streamKey，无法映射 RocketMQ topic: " + streamKey);
        }
        return topic;
    }

    private String toJson(Map<String, String> message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "任务消息序列化失败", e);
        }
    }
}
