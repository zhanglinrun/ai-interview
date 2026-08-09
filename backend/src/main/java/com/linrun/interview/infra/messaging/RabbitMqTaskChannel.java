package com.linrun.interview.infra.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.infra.messaging.AsyncTaskStreamConstants;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RabbitMQ 投递通道。
 *
 * <p>把 {@link TaskQueueChannel} 的逻辑管道键（*_STREAM_KEY）映射为 RabbitMQ routing key，
 * 消息体以 JSON 承载 taskId 等可靠性字段，消费侧复用统一的业务与幂等逻辑。
 */
@Slf4j
@Component
public class RabbitMqTaskChannel implements TaskQueueChannel {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /** 逻辑管道键 → RabbitMQ routing key 映射。 */
    private static final Map<String, String> STREAM_KEY_TO_ROUTING = Map.of(
        AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
        AsyncTaskStreamConstants.RABBIT_RESUME_ANALYZE_ROUTING,
        AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY,
        AsyncTaskStreamConstants.RABBIT_INTERVIEW_EVALUATE_ROUTING,
        AsyncTaskStreamConstants.JOB_INTERVIEW_PREPARE_STREAM_KEY,
        AsyncTaskStreamConstants.RABBIT_JOB_INTERVIEW_PREPARE_ROUTING,
        AsyncTaskStreamConstants.INTERVIEW_REPORT_STREAM_KEY,
        AsyncTaskStreamConstants.RABBIT_INTERVIEW_REPORT_ROUTING
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
                setHeader(msg, "X-Trace-Id", message.get(AsyncTaskStreamConstants.FIELD_TRACE_ID));
                setHeader(msg, "agentRunId", message.get(AsyncTaskStreamConstants.FIELD_AGENT_RUN_ID));
                setHeader(msg, "commandId", message.get(AsyncTaskStreamConstants.FIELD_COMMAND_ID));
                setHeader(msg, "sessionId", message.get(AsyncTaskStreamConstants.FIELD_SESSION_ID));
                return msg;
            });
        return taskId != null ? taskId : "sent";
    }

    private void setHeader(org.springframework.amqp.core.Message message, String name, String value) {
        if (value != null && !value.isBlank()) {
            message.getMessageProperties().setHeader(name, value);
        }
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
