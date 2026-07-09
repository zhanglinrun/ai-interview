package com.linrun.interview.modules.voiceinterview.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.async.AbstractRabbitTaskConsumer;
import com.linrun.interview.common.async.AbstractStreamConsumer;
import com.linrun.interview.common.async.AsyncEngineProperties;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 语音面试评估 RabbitMQ 消费者（app.async.engine=rabbitmq 时启用）。
 * 复用 {@link VoiceEvaluateStreamConsumer} 的业务与幂等逻辑，由 RabbitMQ 驱动投递/重试/死信。
 */
@Component
@ConditionalOnProperty(name = "app.async.engine", havingValue = AsyncEngineProperties.ENGINE_RABBITMQ)
public class VoiceEvaluateRabbitConsumer extends AbstractRabbitTaskConsumer {

    private final VoiceEvaluateStreamConsumer delegate;

    public VoiceEvaluateRabbitConsumer(ObjectMapper objectMapper, VoiceEvaluateStreamConsumer delegate) {
        super(objectMapper);
        this.delegate = delegate;
    }

    @Override
    protected AbstractStreamConsumer<?> delegate() {
        return delegate;
    }

    @RabbitListener(queues = AsyncTaskStreamConstants.RABBIT_VOICE_EVALUATE_QUEUE,
        containerFactory = "taskRabbitListenerContainerFactory")
    public void onMessage(String body) {
        handle(body);
    }
}
