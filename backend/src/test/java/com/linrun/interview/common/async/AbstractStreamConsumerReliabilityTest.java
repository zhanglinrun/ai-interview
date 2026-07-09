package com.linrun.interview.common.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.infrastructure.redis.RedisService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.StreamMessageId;

@DisplayName("Broker 消费者可靠性：幂等去重 / 失败标记 / 死信触发")
@ExtendWith(MockitoExtension.class)
class AbstractStreamConsumerReliabilityTest {

    @Mock
    private RedisService redisService;

    private TestConsumer consumer;

    private static final String GROUP_NAME = "test-group";

    @BeforeEach
    void setUp() {
        consumer = new TestConsumer(redisService);
    }

    private Map<String, String> message(String taskId) {
        Map<String, String> data = new HashMap<>();
        data.put("payload", "p1");
        if (taskId != null) {
            data.put(AsyncTaskStreamConstants.FIELD_TASK_ID, taskId);
        }
        return data;
    }

    private String dedupKey(String taskId) {
        return AsyncTaskStreamConstants.DEDUP_KEY_PREFIX + GROUP_NAME + ":" + taskId;
    }

    @Nested
    @DisplayName("幂等去重")
    class Idempotency {

        @Test
        @DisplayName("任务已标记 DONE 时跳过业务，不再重复执行")
        void skipWhenDone() {
            String taskId = "task-done";
            when(redisService.getIdempotencyState(dedupKey(taskId)))
                .thenReturn(AsyncTaskStreamConstants.DEDUP_STATE_DONE);

            consumer.consumeFromBroker(message(taskId), 0);

            assertThat(consumer.businessRuns.get()).isZero();
            verify(redisService, never()).markIdempotencyDone(anyString(), any());
        }

        @Test
        @DisplayName("成功执行后写入 DONE 标记")
        void markDoneAfterSuccess() {
            String taskId = "task-ok";
            when(redisService.getIdempotencyState(dedupKey(taskId))).thenReturn(null);

            consumer.consumeFromBroker(message(taskId), 0);

            assertThat(consumer.businessRuns.get()).isEqualTo(1);
            assertThat(consumer.completedMarked).isTrue();
            verify(redisService).markIdempotencyDone(eq(dedupKey(taskId)),
                eq(Duration.ofMillis(AsyncTaskStreamConstants.DEDUP_DONE_TTL_MS)));
        }
    }

    @Nested
    @DisplayName("失败与死信")
    class FailureAndDlq {

        @Test
        @DisplayName("业务失败且未达上限时抛出触发重试，不标记 FAILED")
        void throwWhenBelowMax() {
            String taskId = "task-retry";
            when(redisService.getIdempotencyState(dedupKey(taskId))).thenReturn(null);
            consumer.failBusiness = true;

            assertThatThrownBy(() -> consumer.consumeFromBroker(message(taskId), 0))
                .isInstanceOf(IllegalStateException.class);

            assertThat(consumer.failedMarked).isFalse();
            verify(redisService, never()).markIdempotencyDone(anyString(), any());
        }

        @Test
        @DisplayName("业务失败且达到上限时标记 FAILED 并抛出（交 broker 路由死信）")
        void markFailedWhenExhausted() {
            String taskId = "task-dead";
            when(redisService.getIdempotencyState(dedupKey(taskId))).thenReturn(null);
            consumer.failBusiness = true;

            assertThatThrownBy(() -> consumer.consumeFromBroker(
                message(taskId), AsyncTaskStreamConstants.MAX_RETRY_COUNT))
                .isInstanceOf(IllegalStateException.class);

            assertThat(consumer.failedMarked).isTrue();
        }
    }

    @Nested
    @DisplayName("向后兼容")
    class BackwardCompatibility {

        @Test
        @DisplayName("消息无 taskId 时跳过幂等保护，仍执行业务")
        void noTaskIdSkipsIdempotency() {
            consumer.consumeFromBroker(message(null), 0);

            assertThat(consumer.businessRuns.get()).isEqualTo(1);
            assertThat(consumer.completedMarked).isTrue();
            verify(redisService, never()).getIdempotencyState(anyString());
            verify(redisService, never()).markIdempotencyDone(anyString(), any());
        }
    }

    /**
     * 最小可测子类：业务执行计数，可切换为失败以驱动失败 / 死信路径。
     */
    private static class TestConsumer extends AbstractStreamConsumer<String> {

        final AtomicInteger businessRuns = new AtomicInteger(0);
        boolean failBusiness = false;
        boolean failedMarked = false;
        boolean completedMarked = false;

        TestConsumer(RedisService redisService) {
            super(redisService);
        }

        @Override
        protected String taskDisplayName() {
            return "测试";
        }

        @Override
        protected String groupName() {
            return GROUP_NAME;
        }

        @Override
        protected String parsePayload(StreamMessageId messageId, Map<String, String> data) {
            return data.get("payload");
        }

        @Override
        protected String payloadIdentifier(String payload) {
            return "payload=" + payload;
        }

        @Override
        protected void markProcessing(String payload) {
            // no-op
        }

        @Override
        protected void processBusiness(String payload) {
            businessRuns.incrementAndGet();
            if (failBusiness) {
                throw new IllegalStateException("模拟业务失败");
            }
        }

        @Override
        protected void markCompleted(String payload) {
            completedMarked = true;
        }

        @Override
        protected void markFailed(String payload, String error) {
            failedMarked = true;
        }
    }
}
