package com.linrun.interview.common.async;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * RocketMQ 消费者模板基类（app.async.engine=rocketmq 时子类装配）。
 *
 * <p>只负责「解析 JSON 消息体 → 委托 {@link AbstractStreamConsumer#consumeFromBroker}」，
 * 业务处理、幂等去重与 Redis Stream 路径完全复用同一实现（{@link #delegate()} 返回的消费者 Bean
 * 在 rocketmq 引擎下不启动 Redis 轮询，仅作为业务处理器存在）。
 *
 * <p>通过 {@link RocketMQPushConsumerLifecycleListener} 把 {@code maxReconsumeTimes} 设为
 * {@link AsyncTaskStreamConstants#MAX_RETRY_COUNT}（3）——不依赖注解属性，跨 starter 版本稳定；
 * 重试耗尽后 broker 自动把消息投递到 {@code %DLQ%<consumerGroup>} 死信 topic。
 */
@Slf4j
public abstract class AbstractRocketTaskConsumer
    implements RocketMQListener<MessageExt>, RocketMQPushConsumerLifecycleListener {

    private final ObjectMapper objectMapper;

    protected AbstractRocketTaskConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 复用其业务/幂等逻辑的 Redis Stream 消费者 Bean（rocketmq 引擎下不轮询 Redis）。 */
    protected abstract AbstractStreamConsumer<?> delegate();

    @Override
    public void onMessage(MessageExt message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        Map<String, String> data;
        try {
            data = objectMapper.readValue(body, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            // 脏消息无法解析：直接丢弃（返回视为消费成功），避免无限重试
            log.error("RocketMQ 消息体解析失败，丢弃: msgId={}, body={}", message.getMsgId(), body, e);
            return;
        }
        delegate().consumeFromBroker(data, message.getReconsumeTimes());
    }

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setMaxReconsumeTimes(AsyncTaskStreamConstants.MAX_RETRY_COUNT);
    }
}
