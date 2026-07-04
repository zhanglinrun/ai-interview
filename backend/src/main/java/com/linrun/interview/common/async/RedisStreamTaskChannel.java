package com.linrun.interview.common.async;

import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.infrastructure.redis.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Redis Stream 投递通道（{@code app.async.engine=redis-stream} 回退引擎时启用）。
 *
 * <p>默认引擎为 RocketMQ；仅在显式设置 {@code redis-stream} 时装配本通道，此时
 * {@link RocketMqEngineConfig} 不加载 RocketMQ 自动配置，无需 broker/name-server。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.async.engine", havingValue = AsyncEngineProperties.ENGINE_REDIS_STREAM)
public class RedisStreamTaskChannel implements TaskQueueChannel {

    private final RedisService redisService;

    @Override
    public String send(String streamKey, Map<String, String> message) {
        return redisService.streamAdd(streamKey, message, AsyncTaskStreamConstants.STREAM_MAX_LEN);
    }
}
