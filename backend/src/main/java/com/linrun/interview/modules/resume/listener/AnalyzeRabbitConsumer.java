package com.linrun.interview.modules.resume.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.async.AbstractRabbitTaskConsumer;
import com.linrun.interview.common.async.AbstractStreamConsumer;
import com.linrun.interview.common.async.AsyncEngineProperties;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 简历分析 RabbitMQ 消费者（app.async.engine=rabbitmq 时启用）。
 * 复用 {@link AnalyzeStreamConsumer} 的业务与幂等逻辑，仅由 RabbitMQ 驱动投递/重试/死信。
 */
@Component
@ConditionalOnProperty(name = "app.async.engine", havingValue = AsyncEngineProperties.ENGINE_RABBITMQ)
public class AnalyzeRabbitConsumer extends AbstractRabbitTaskConsumer {

    private final AnalyzeStreamConsumer delegate;

    public AnalyzeRabbitConsumer(ObjectMapper objectMapper, AnalyzeStreamConsumer delegate) {
        super(objectMapper);
        this.delegate = delegate;
    }

    @Override
    protected AbstractStreamConsumer<?> delegate() {
        return delegate;
    }

    @RabbitListener(queues = AsyncTaskStreamConstants.RABBIT_RESUME_ANALYZE_QUEUE,
        containerFactory = "taskRabbitListenerContainerFactory")
    public void onMessage(String body) {
        handle(body);
    }
}
