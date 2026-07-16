package com.linrun.interview.common.async;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 返回当前已重试次数（首次为 0）。达到 {@link com.linrun.interview.common.constant.AsyncTaskStreamConstants#MAX_RETRY_COUNT}
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

    private int currentRetryCount() {
        RetryContext context = RetrySynchronizationManager.getContext();
        return context != null ? context.getRetryCount() : 0;
    }
}
