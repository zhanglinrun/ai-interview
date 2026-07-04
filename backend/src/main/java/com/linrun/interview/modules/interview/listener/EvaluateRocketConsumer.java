package com.linrun.interview.modules.interview.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.async.AbstractRocketTaskConsumer;
import com.linrun.interview.common.async.AbstractStreamConsumer;
import com.linrun.interview.common.async.AsyncEngineProperties;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 面试评估 RocketMQ 消费者（app.async.engine=rocketmq 时启用）。
 * 复用 {@link EvaluateStreamConsumer} 的业务与幂等逻辑；配合事务消息保证
 * 「面试完成 → 评估任务」最终一致，重试耗尽进入 %DLQ%evaluate-group。
 */
@Component
@ConditionalOnProperty(name = "app.async.engine", havingValue = AsyncEngineProperties.ENGINE_ROCKETMQ,
    matchIfMissing = true)
@RocketMQMessageListener(
    topic = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_TOPIC,
    consumerGroup = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME)
public class EvaluateRocketConsumer extends AbstractRocketTaskConsumer {

    private final EvaluateStreamConsumer delegate;

    public EvaluateRocketConsumer(ObjectMapper objectMapper, EvaluateStreamConsumer delegate) {
        super(objectMapper);
        this.delegate = delegate;
    }

    @Override
    protected AbstractStreamConsumer<?> delegate() {
        return delegate;
    }
}
