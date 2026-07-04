package com.linrun.interview.common.async;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 异步任务引擎配置。
 *
 * <p>{@code app.async.engine} 支持两种取值：
 * <ul>
 *   <li>{@code rocketmq}（默认）：可靠性方案，RocketMQ 5.x 事务消息 + broker 重试 + 原生死信队列</li>
 *   <li>{@code redis-stream}：轻量回退方案，Redis Stream + 消费者组 + autoClaim + 手工 DLQ，
 *       broker 故障时一行配置回退、无需 name-server</li>
 * </ul>
 * 两种引擎共用同一套业务消费逻辑（{@link AbstractStreamConsumer} 子类），一行配置互切。
 * 默认（含属性缺失）走 RocketMQ，与 {@link RocketMqEngineConfig} 的 {@code matchIfMissing=true} 一致。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.async")
public class AsyncEngineProperties {

    public static final String ENGINE_REDIS_STREAM = "redis-stream";
    public static final String ENGINE_ROCKETMQ = "rocketmq";

    /** 异步任务引擎：rocketmq（默认）| redis-stream */
    private String engine = ENGINE_ROCKETMQ;

    public boolean isRedisStream() {
        return ENGINE_REDIS_STREAM.equalsIgnoreCase(engine);
    }

    public boolean isRocketMq() {
        return !ENGINE_REDIS_STREAM.equalsIgnoreCase(engine);
    }
}
