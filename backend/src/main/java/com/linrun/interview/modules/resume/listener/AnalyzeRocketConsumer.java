package com.linrun.interview.modules.resume.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.async.AbstractRocketTaskConsumer;
import com.linrun.interview.common.async.AbstractStreamConsumer;
import com.linrun.interview.common.async.AsyncEngineProperties;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 简历分析 RocketMQ 消费者（app.async.engine=rocketmq 时启用）。
 * 复用 {@link AnalyzeStreamConsumer} 的业务与幂等逻辑，仅由 RocketMQ 驱动投递/重试/死信。
 */
@Component
@ConditionalOnProperty(name = "app.async.engine", havingValue = AsyncEngineProperties.ENGINE_ROCKETMQ,
    matchIfMissing = true)
@RocketMQMessageListener(
    topic = AsyncTaskStreamConstants.RESUME_ANALYZE_TOPIC,
    consumerGroup = AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME)
public class AnalyzeRocketConsumer extends AbstractRocketTaskConsumer {

    private final AnalyzeStreamConsumer delegate;

    public AnalyzeRocketConsumer(ObjectMapper objectMapper, AnalyzeStreamConsumer delegate) {
        super(objectMapper);
        this.delegate = delegate;
    }

    @Override
    protected AbstractStreamConsumer<?> delegate() {
        return delegate;
    }
}
