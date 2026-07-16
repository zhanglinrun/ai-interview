package com.linrun.interview.modules.interview.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.async.AbstractRabbitTaskConsumer;
import com.linrun.interview.common.async.AbstractStreamConsumer;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 面试评估 RabbitMQ 消费者。
 * 复用 {@link EvaluateStreamConsumer} 的业务与幂等逻辑，由 RabbitMQ 驱动投递/重试/死信。
 */
@Component
public class EvaluateRabbitConsumer extends AbstractRabbitTaskConsumer {

    private final EvaluateStreamConsumer delegate;

    public EvaluateRabbitConsumer(ObjectMapper objectMapper, EvaluateStreamConsumer delegate) {
        super(objectMapper);
        this.delegate = delegate;
    }

    @Override
    protected AbstractStreamConsumer<?> delegate() {
        return delegate;
    }

    @RabbitListener(queues = AsyncTaskStreamConstants.RABBIT_INTERVIEW_EVALUATE_QUEUE,
        containerFactory = "taskRabbitListenerContainerFactory")
    public void onMessage(String body) {
        handle(body);
    }
}
