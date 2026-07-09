package com.linrun.interview.common.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RabbitMQ 投递通道（{@code app.async.engine=rabbitmq} 时启用）。
 *
 * <p>把 {@link TaskQueueChannel} 的逻辑管道键（*_STREAM_KEY）映射为 RabbitMQ routing key，
 * 消息体以 JSON 承载与其它引擎完全一致的字段（含 taskId / retryCount），消费侧解析后复用
 * {@link AbstractStreamConsumer#consumeFromBroker} 同一套业务与幂等逻辑，实现「一行配置切换引擎」。
 *
 * <p>RabbitMQ 无 RocketMQ 式事务半消息，{@link #sendInTransaction} 沿用父接口默认（等同 {@link #send}）：
 * 业务侧已遵循 DB-first 顺序 + 补偿任务兜底，保证「DB 状态变更」与「消息投递」最终一致。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.async.engine", havingValue = AsyncEngineProperties.ENGINE_RABBITMQ)
public class RabbitMqTaskChannel implements TaskQueueChannel {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /** 逻辑管道键 → RabbitMQ routing key 映射。 */
    private static final Map<String, String> STREAM_KEY_TO_ROUTING = Map.of(
        AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
        AsyncTaskStreamConstants.RABBIT_RESUME_ANALYZE_ROUTING,
        AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY,
        AsyncTaskStreamConstants.RABBIT_INTERVIEW_EVALUATE_ROUTING,
        AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY,
        AsyncTaskStreamConstants.RABBIT_VOICE_EVALUATE_ROUTING
    );

    public RabbitMqTaskChannel(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        log.info("异步任务引擎: RabbitMQ（TaskQueueChannel=RabbitMqTaskChannel）");
    }

    @Override
    public String send(String streamKey, Map<String, String> message) {
        String routingKey = resolveRouting(streamKey);
        String taskId = message.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
        String json = toJson(message);
        rabbitTemplate.convertAndSend(AsyncTaskStreamConstants.RABBIT_TASK_EXCHANGE, routingKey, json,
            msg -> {
                if (taskId != null) {
                    // messageId 便于在 RabbitMQ 管理台按 taskId 检索消息
                    msg.getMessageProperties().setMessageId(taskId);
                }
                return msg;
            });
        return taskId != null ? taskId : "sent";
    }

    private String resolveRouting(String streamKey) {
        String routingKey = STREAM_KEY_TO_ROUTING.get(streamKey);
        if (routingKey == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "未知的异步管道 streamKey，无法映射 RabbitMQ routing key: " + streamKey);
        }
        return routingKey;
    }

    private String toJson(Map<String, String> message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "任务消息序列化失败", e);
        }
    }
}
