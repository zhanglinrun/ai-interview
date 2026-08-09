package com.linrun.interview.infra.messaging;

import com.linrun.interview.infra.messaging.AsyncTaskStreamConstants;
import com.linrun.interview.infra.observability.TraceContext;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 异步任务生产者模板基类。
 * 统一 RabbitMQ 消息发送骨架与失败处理逻辑。
 */
@Slf4j
public abstract class AbstractStreamProducer<T> {

    private final TaskQueueChannel taskQueueChannel;

    protected AbstractStreamProducer(TaskQueueChannel taskQueueChannel) {
        this.taskQueueChannel = taskQueueChannel;
    }

    protected void sendTask(T payload) {
        doSend(payload);
    }

    private void doSend(T payload) {
        // 为每条任务生成稳定的 taskId 作为幂等去重键：消费侧据此保证"同一任务只真正执行一次"，
        // 重新入队重试、被认领接管时该 ID 都保持不变。子类的 buildMessage 返回不可变 Map，
        // 这里复制为可变 Map 后注入，子类无需感知。
        Map<String, String> message = new HashMap<>(buildMessage(payload));
        message.putIfAbsent(AsyncTaskStreamConstants.FIELD_TASK_ID, UUID.randomUUID().toString());
        String traceId = TraceContext.getTraceId();
        if (traceId != null && !traceId.isBlank()) {
            message.putIfAbsent(AsyncTaskStreamConstants.FIELD_TRACE_ID, traceId);
        }
        try {
            String messageId = taskQueueChannel.send(streamKey(), message);
            log.info("{}任务已发送到 RabbitMQ: {}, messageId={}, taskId={}",
                taskDisplayName(), payloadIdentifier(payload), messageId,
                message.get(AsyncTaskStreamConstants.FIELD_TASK_ID));
        } catch (Exception e) {
            log.error("发送{}任务失败: {}, error={}",
                taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
            onSendFailed(payload, "任务入队失败: " + e.getMessage());
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract Map<String, String> buildMessage(T payload);

    protected abstract String payloadIdentifier(T payload);

    protected abstract void onSendFailed(T payload, String error);
}
