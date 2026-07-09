package com.linrun.interview.common.async;

import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.StreamMessageId;

import java.time.Duration;
import java.util.Map;

/**
 * 异步任务消费者业务模板基类（broker 驱动）。
 *
 * <p>两种引擎（RocketMQ / RabbitMQ）的监听器解析消息后统一回调 {@link #consumeFromBroker}，
 * 复用同一套「幂等去重 + 业务处理 + 失败标记」逻辑；投递、重试与死信路由交给各自的 broker。
 * 幂等去重基于 Redis KV（{@code async:dedup:<group>:<taskId>}），保证「同一 taskId 只真正执行一次」。
 */
@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final RedisService redisService;

    protected AbstractStreamConsumer(RedisService redisService) {
        this.redisService = redisService;
    }

    /**
     * broker 引擎消费入口：broker 负责投递、重试与死信路由，这里只保留「幂等去重 + 业务处理」。
     *
     * <p>处理失败时抛出异常触发 broker 重试；达到 {@link AsyncTaskStreamConstants#MAX_RETRY_COUNT}
     * 后由 broker（RocketMQ 原生 %DLQ% / RabbitMQ DLX→DLQ）路由到死信队列。
     *
     * @param data           消息字段（含 taskId）
     * @param reconsumeTimes broker 已重试次数（0 表示首次投递）
     */
    public void consumeFromBroker(Map<String, String> data, int reconsumeTimes) {
        T payload = parsePayload(null, data);
        if (payload == null) {
            // 消息格式错误：视为消费成功丢弃，避免无限重试脏消息
            return;
        }
        String taskId = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
        String dedupKey = dedupKey(taskId);
        if (dedupKey != null
            && AsyncTaskStreamConstants.DEDUP_STATE_DONE.equals(redisService.getIdempotencyState(dedupKey))) {
            log.info("{} task 已完成，跳过重复执行(broker): {}, taskId={}",
                taskDisplayName(), payloadIdentifier(payload), taskId);
            return;
        }
        log.info("Processing {} task(broker): payload={}, reconsumeTimes={}, taskId={}",
            taskDisplayName(), payloadIdentifier(payload), reconsumeTimes, taskId);
        try {
            markProcessing(payload);
            processBusiness(payload);
            markCompleted(payload);
            if (dedupKey != null) {
                redisService.markIdempotencyDone(
                    dedupKey, Duration.ofMillis(AsyncTaskStreamConstants.DEDUP_DONE_TTL_MS));
            }
            log.info("{} task completed(broker): {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("{} task failed(broker): {}, reconsumeTimes={}",
                taskDisplayName(), payloadIdentifier(payload), reconsumeTimes, e);
            // 达到 broker 重试上限：标记 FAILED（此后进入死信队列，由告警消费者兜底）
            if (reconsumeTimes >= AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                markFailed(payload, truncateError(taskDisplayName()
                    + " failed after broker retry " + reconsumeTimes + ": " + e.getMessage()));
            }
            // 抛出触发 broker 重试 / 死信路由
            throw new IllegalStateException(taskDisplayName() + " 消费失败，触发 broker 重试", e);
        }
    }

    /**
     * 由 taskId 推导幂等去重键。taskId 为空（历史消息或未携带）时返回 null，跳过幂等保护，
     * 退化为"至少一次"语义，保证向后兼容。
     */
    protected String dedupKey(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        return AsyncTaskStreamConstants.DEDUP_KEY_PREFIX + groupName() + ":" + taskId;
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    protected RedisService redisService() {
        return redisService;
    }

    protected abstract String taskDisplayName();

    /** 消费者组名：用于幂等去重键分组（RocketMQ/RabbitMQ 消费者组也沿用它）。 */
    protected abstract String groupName();

    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);

    protected abstract String payloadIdentifier(T payload);

    protected abstract void markProcessing(T payload);

    protected abstract void processBusiness(T payload);

    protected abstract void markCompleted(T payload);

    protected abstract void markFailed(T payload, String error);
}
