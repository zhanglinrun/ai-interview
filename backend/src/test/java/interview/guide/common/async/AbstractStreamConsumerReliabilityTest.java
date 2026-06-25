package interview.guide.common.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
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

@DisplayName("Stream 消费者可靠性：幂等去重 / 重试 / 死信")
@ExtendWith(MockitoExtension.class)
class AbstractStreamConsumerReliabilityTest {

    @Mock
    private RedisService redisService;

    private TestConsumer consumer;

    private static final String STREAM_KEY = "test:stream";
    private static final String GROUP_NAME = "test-group";
    private static final StreamMessageId MSG_ID = new StreamMessageId(1, 0);

    @BeforeEach
    void setUp() {
        consumer = new TestConsumer(redisService);
    }

    private Map<String, String> message(String taskId, String retryCount) {
        Map<String, String> data = new HashMap<>();
        data.put("payload", "p1");
        if (taskId != null) {
            data.put(AsyncTaskStreamConstants.FIELD_TASK_ID, taskId);
        }
        data.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, retryCount);
        return data;
    }

    @Nested
    @DisplayName("幂等去重")
    class Idempotency {

        @Test
        @DisplayName("任务已标记 DONE 时跳过业务并直接 ACK")
        void skipWhenDone() {
            String taskId = "task-done";
            String dedupKey = AsyncTaskStreamConstants.DEDUP_KEY_PREFIX + GROUP_NAME + ":" + taskId;
            when(redisService.getIdempotencyState(dedupKey))
                .thenReturn(AsyncTaskStreamConstants.DEDUP_STATE_DONE);

            consumer.processMessage(MSG_ID, message(taskId, "0"));

            assertThat(consumer.businessRuns.get()).isZero();
            verify(redisService).streamAck(STREAM_KEY, GROUP_NAME, MSG_ID);
            verify(redisService, never()).tryAcquireIdempotency(anyString(), any());
        }

        @Test
        @DisplayName("成功执行后写入 DONE 标记并 ACK")
        void markDoneAfterSuccess() {
            String taskId = "task-ok";
            String dedupKey = AsyncTaskStreamConstants.DEDUP_KEY_PREFIX + GROUP_NAME + ":" + taskId;
            when(redisService.getIdempotencyState(dedupKey)).thenReturn(null);
            when(redisService.tryAcquireIdempotency(eq(dedupKey), any())).thenReturn(true);

            consumer.processMessage(MSG_ID, message(taskId, "0"));

            assertThat(consumer.businessRuns.get()).isEqualTo(1);
            verify(redisService).markIdempotencyDone(eq(dedupKey),
                eq(Duration.ofMillis(AsyncTaskStreamConstants.DEDUP_DONE_TTL_MS)));
            verify(redisService).streamAck(STREAM_KEY, GROUP_NAME, MSG_ID);
        }

        @Test
        @DisplayName("占位被他人持有(PROCESSING)时让出执行且不 ACK")
        void yieldWhenProcessingByOther() {
            String taskId = "task-busy";
            String dedupKey = AsyncTaskStreamConstants.DEDUP_KEY_PREFIX + GROUP_NAME + ":" + taskId;
            when(redisService.getIdempotencyState(dedupKey))
                .thenReturn(null)
                .thenReturn(AsyncTaskStreamConstants.DEDUP_STATE_PROCESSING);
            when(redisService.tryAcquireIdempotency(eq(dedupKey), any())).thenReturn(false);

            consumer.processMessage(MSG_ID, message(taskId, "0"));

            assertThat(consumer.businessRuns.get()).isZero();
            verify(redisService, never()).streamAck(any(), any(), any());
        }

        @Test
        @DisplayName("抢占失败但任务已被他人完成(DONE)时跳过业务并 ACK")
        void ackWhenDoneAfterFailedAcquire() {
            String taskId = "task-raced-done";
            String dedupKey = AsyncTaskStreamConstants.DEDUP_KEY_PREFIX + GROUP_NAME + ":" + taskId;
            // 首查未完成；抢占占位失败后再查，发现另一消费者恰好已写入 DONE
            when(redisService.getIdempotencyState(dedupKey))
                .thenReturn(null)
                .thenReturn(AsyncTaskStreamConstants.DEDUP_STATE_DONE);
            when(redisService.tryAcquireIdempotency(eq(dedupKey), any())).thenReturn(false);

            consumer.processMessage(MSG_ID, message(taskId, "0"));

            assertThat(consumer.businessRuns.get()).isZero();
            verify(redisService).streamAck(STREAM_KEY, GROUP_NAME, MSG_ID);
        }
    }

    @Nested
    @DisplayName("重试与死信")
    class RetryAndDlq {

        @Test
        @DisplayName("业务失败且未达上限时重新入队，并释放处理中占位")
        void retryWhenBelowMax() {
            String taskId = "task-retry";
            String dedupKey = AsyncTaskStreamConstants.DEDUP_KEY_PREFIX + GROUP_NAME + ":" + taskId;
            when(redisService.getIdempotencyState(dedupKey)).thenReturn(null);
            when(redisService.tryAcquireIdempotency(eq(dedupKey), any())).thenReturn(true);
            consumer.failBusiness = true;

            consumer.processMessage(MSG_ID, message(taskId, "0"));

            // 重新入队（retryCount=1）走 streamAdd 到原 Stream
            verify(redisService).streamAdd(eq(STREAM_KEY), any(), eq(AsyncTaskStreamConstants.STREAM_MAX_LEN));
            verify(redisService).releaseIdempotencyIfProcessing(dedupKey);
            verify(redisService, never()).streamAddDlq(anyString(), any());
            verify(redisService).streamAck(STREAM_KEY, GROUP_NAME, MSG_ID);
        }

        @Test
        @DisplayName("重试耗尽时转入死信队列并标记失败")
        void toDlqWhenExhausted() {
            String taskId = "task-dead";
            String dedupKey = AsyncTaskStreamConstants.DEDUP_KEY_PREFIX + GROUP_NAME + ":" + taskId;
            when(redisService.getIdempotencyState(dedupKey)).thenReturn(null);
            when(redisService.tryAcquireIdempotency(eq(dedupKey), any())).thenReturn(true);
            consumer.failBusiness = true;

            // retryCount 已达最大值，应转死信而非再次入队
            consumer.processMessage(MSG_ID,
                message(taskId, String.valueOf(AsyncTaskStreamConstants.MAX_RETRY_COUNT)));

            verify(redisService).streamAddDlq(
                eq(STREAM_KEY + AsyncTaskStreamConstants.DLQ_STREAM_SUFFIX), any());
            assertThat(consumer.failedMarked).isTrue();
            verify(redisService).streamAck(STREAM_KEY, GROUP_NAME, MSG_ID);
        }
    }

    @Nested
    @DisplayName("向后兼容")
    class BackwardCompatibility {

        @Test
        @DisplayName("消息无 taskId 时跳过幂等保护，仍执行业务")
        void noTaskIdSkipsIdempotency() {
            consumer.processMessage(MSG_ID, message(null, "0"));

            assertThat(consumer.businessRuns.get()).isEqualTo(1);
            verify(redisService, never()).getIdempotencyState(anyString());
            verify(redisService, never()).tryAcquireIdempotency(anyString(), any());
            verify(redisService).streamAck(STREAM_KEY, GROUP_NAME, MSG_ID);
        }
    }

    /**
     * 最小可测子类：业务执行计数，可切换为失败以驱动重试 / 死信路径。
     */
    private static class TestConsumer extends AbstractStreamConsumer<String> {

        final AtomicInteger businessRuns = new AtomicInteger(0);
        boolean failBusiness = false;
        boolean failedMarked = false;

        TestConsumer(RedisService redisService) {
            super(redisService);
        }

        @Override
        protected String taskDisplayName() {
            return "测试";
        }

        @Override
        protected String streamKey() {
            return STREAM_KEY;
        }

        @Override
        protected String groupName() {
            return GROUP_NAME;
        }

        @Override
        protected String consumerPrefix() {
            return "test-consumer-";
        }

        @Override
        protected String threadName() {
            return "test-consumer";
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
            // no-op
        }

        @Override
        protected void markFailed(String payload, String error) {
            failedMarked = true;
        }

        @Override
        protected Map<String, String> buildRetryMessage(String payload, int retryCount) {
            Map<String, String> message = new HashMap<>();
            message.put("payload", payload);
            message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
            return message;
        }
    }
}
