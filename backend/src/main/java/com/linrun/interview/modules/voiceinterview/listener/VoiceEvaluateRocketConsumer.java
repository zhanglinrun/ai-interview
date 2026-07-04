package com.linrun.interview.modules.voiceinterview.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.async.AbstractRocketTaskConsumer;
import com.linrun.interview.common.async.AbstractStreamConsumer;
import com.linrun.interview.common.async.AsyncEngineProperties;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 语音面试评估 RocketMQ 消费者（app.async.engine=rocketmq 时启用）。
 * 复用 {@link VoiceEvaluateStreamConsumer} 的业务与幂等逻辑，由 RocketMQ 驱动投递/重试/死信。
 */
@Component
@ConditionalOnProperty(name = "app.async.engine", havingValue = AsyncEngineProperties.ENGINE_ROCKETMQ,
    matchIfMissing = true)
@RocketMQMessageListener(
    topic = AsyncTaskStreamConstants.VOICE_EVALUATE_TOPIC,
    consumerGroup = AsyncTaskStreamConstants.VOICE_EVALUATE_GROUP_NAME)
public class VoiceEvaluateRocketConsumer extends AbstractRocketTaskConsumer {

    private final VoiceEvaluateStreamConsumer delegate;

    public VoiceEvaluateRocketConsumer(ObjectMapper objectMapper, VoiceEvaluateStreamConsumer delegate) {
        super(objectMapper);
        this.delegate = delegate;
    }

    @Override
    protected AbstractStreamConsumer<?> delegate() {
        return delegate;
    }
}
