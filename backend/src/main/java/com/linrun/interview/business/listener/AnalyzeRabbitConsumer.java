package com.linrun.interview.business.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.infra.messaging.AbstractRabbitTaskConsumer;
import com.linrun.interview.infra.messaging.AbstractStreamConsumer;
import com.linrun.interview.infra.messaging.AsyncTaskStreamConstants;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/**
 * 简历分析 RabbitMQ 消费者。
 * 复用 {@link AnalyzeStreamConsumer} 的业务与幂等逻辑，仅由 RabbitMQ 驱动投递/重试/死信。
 */
@Component
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
    public void onMessage(Message message) {
        handle(message);
    }
}
