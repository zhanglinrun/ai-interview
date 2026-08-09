package com.linrun.interview.infra.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.infra.observability.TraceContext;
import com.linrun.interview.infra.observability.TraceIdPolicy;
import org.springframework.amqp.core.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetrySynchronizationManager;

import java.util.Map;

/**
 * RabbitMQ 消费者模板基类。
 *
 * <p>只负责「解析 JSON 消息体 → 委托 {@link AbstractStreamConsumer#consumeFromBroker}」，
 * 业务处理和幂等去重复用 {@link #delegate()} 返回的消费者 Bean。
 *
 * <p>重试次数取自监听容器重试建议链的 {@link RetryContext}：无状态重试期间 {@code getRetryCount()}
 * 返回当前已重试次数（首次为 0）。达到 {@link com.linrun.interview.infra.messaging.AsyncTaskStreamConstants#MAX_RETRY_COUNT}
 * 时 {@code consumeFromBroker} 标记 FAILED，随后抛出触发重试耗尽 → DLX → DLQ。
 */
@Slf4j
public abstract class AbstractRabbitTaskConsumer {

    private final ObjectMapper objectMapper;

    protected AbstractRabbitTaskConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 复用其业务/幂等逻辑的消费者 Bean。 */
    protected abstract AbstractStreamConsumer<?> delegate();

    /**
     * 由子类的 {@code @RabbitListener} 方法调用：解析消息体并委托业务处理。
     * 脏消息（无法解析）直接丢弃（正常返回视为消费成功），避免无限重试。
     */
    protected void handle(String body) {
        Map<String, String> data;
        try {
            data = objectMapper.readValue(body, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.error("RabbitMQ 消息体解析失败，丢弃: bodyLength={}",
                body == null ? 0 : body.length(), e);
            return;
        }
        delegate().consumeFromBroker(data, currentRetryCount());
    }

    /** Message-aware entry point that restores producer context for this delivery. */
    protected void handle(Message message) {
        if (message == null) {
            return;
        }
        Object requested = message.getMessageProperties().getHeaders().get("X-Trace-Id");
        String traceId = requested == null ? null : String.valueOf(requested);
        String accepted = TraceIdPolicy.isValid(traceId) ? traceId : TraceIdPolicy.generate();
        try (TraceContext.Scope ignored = TraceContext.restore(
            new TraceContext.Snapshot(accepted,
                java.util.Map.of(TraceContext.MDC_TRACE_ID, accepted)))) {
            handle(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            // restore scope returns the listener thread to its previous state.
        }
    }

    private int currentRetryCount() {
        RetryContext context = RetrySynchronizationManager.getContext();
        return context != null ? context.getRetryCount() : 0;
    }
}
