package com.linrun.interview.common.async;

import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * RocketMQ 自动装配开关（仅 {@code app.async.engine=rocketmq}（默认）时加载）。
 *
 * <p>RocketMQ Spring Boot Starter 2.3.x 仍用已废弃的 {@code spring.factories}，Spring Boot 3
 * 不再识别，需手动 {@code @Import(RocketMQAutoConfiguration.class)} 才能装配 RocketMQTemplate /
 * 监听容器 / 事务处理器。
 *
 * <p>这里用 {@link ConditionalOnProperty} 把它绑定到异步引擎开关，好处是两种引擎彻底解耦：
 * <ul>
 *   <li>{@code engine=rocketmq}（默认）：装配 RocketMQ，配合 {@code rocketmq.name-server} 连接 broker；</li>
 *   <li>{@code engine=redis-stream}（回退）：完全不加载 RocketMQ 自动配置，无需 broker/name-server，
 *       退化为纯 Redis Stream，实现「broker 故障时一行配置回退」。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "app.async.engine", havingValue = AsyncEngineProperties.ENGINE_ROCKETMQ,
    matchIfMissing = true)
@Import(RocketMQAutoConfiguration.class)
public class RocketMqEngineConfig {
}
