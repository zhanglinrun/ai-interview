package com.linrun.interview.common.async;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 异步任务引擎配置。
 *
 * <p>{@code app.async.engine} 支持两种取值：
 * <ul>
 *   <li>{@code rocketmq}（默认）：RocketMQ 5.x 事务消息 + broker 重试 + 原生死信队列</li>
 *   <li>{@code rabbitmq}：RabbitMQ direct exchange + 每管道队列 + DLX/DLQ + 重试建议链，
 *       Windows/WSL2 本机开发下 RocketMQ 容器 9876 连不通时的推荐引擎（真正的消息队列）</li>
 * </ul>
 * 两种引擎都是 broker 驱动，共用同一套业务消费逻辑（{@link AbstractStreamConsumer#consumeFromBroker}）：
 * RocketMQ 监听器与 RabbitMQ 监听器解析消息后回调 {@code consumeFromBroker}，一行配置互切。
 * 默认（含属性缺失）走 RocketMQ，与 {@link RocketMqEngineConfig} 的 {@code matchIfMissing=true} 一致。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.async")
public class AsyncEngineProperties {

    public static final String ENGINE_ROCKETMQ = "rocketmq";
    public static final String ENGINE_RABBITMQ = "rabbitmq";

    /** 异步任务引擎：rocketmq（默认）| rabbitmq */
    private String engine = ENGINE_ROCKETMQ;

    public boolean isRabbitMq() {
        return ENGINE_RABBITMQ.equalsIgnoreCase(engine);
    }

    public boolean isRocketMq() {
        return !ENGINE_RABBITMQ.equalsIgnoreCase(engine);
    }
}
