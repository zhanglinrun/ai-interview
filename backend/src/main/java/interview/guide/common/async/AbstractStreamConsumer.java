package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final RedisService redisService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private ExecutorService workerPool;
    private ScheduledExecutorService claimScheduler;
    private String consumerName;

    protected AbstractStreamConsumer(RedisService redisService) {
        this.redisService = redisService;
    }

    /**
     * 工作线程数。默认 1 表示在消费线程内串行处理（历史行为）。
     * 子类返回大于 1 时，消费线程只负责拉取，实际业务交给工作线程池并行执行，
     * 实现文档级并行。
     */
    protected int workerPoolSize() {
        return 1;
    }

    @PostConstruct
    public void init() {
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        this.executorService = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, threadName());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );

        int poolSize = Math.max(1, workerPoolSize());
        if (poolSize > 1) {
            AtomicInteger seq = new AtomicInteger(0);
            // 有界队列 + CallerRunsPolicy：队列满时由消费线程兜底执行，形成自然背压，
            // 避免拉取速度远超处理速度时任务无限堆积。
            this.workerPool = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(poolSize * 4),
                r -> {
                    Thread t = new Thread(r, threadName() + "-worker-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
            );
            log.info("{} consumer 启用文档级并行: workers={}", taskDisplayName(), poolSize);
        }

        running.set(true);
        executorService.submit(this::startConsumer);

        // 后台认领线程：周期性接管崩溃消费者遗留在待确认列表（PEL）里的超时消息，
        // 使消息不会因原消费者下线而永久卡住。
        this.claimScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName() + "-claim");
            t.setDaemon(true);
            return t;
        });
        this.claimScheduler.scheduleWithFixedDelay(
            this::claimPendingMessages,
            AsyncTaskStreamConstants.CLAIM_SCAN_INTERVAL_MS,
            AsyncTaskStreamConstants.CLAIM_SCAN_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        log.info("{} consumer started: consumerName={}", taskDisplayName(), consumerName);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (claimScheduler != null) {
            claimScheduler.shutdown();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        if (workerPool != null) {
            workerPool.shutdown();
        }
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
    }

    private void startConsumer() {
        try {
            redisService.createStreamGroup(streamKey(), groupName());
            log.info("Redis Stream group is ready: {}", groupName());
        } catch (Exception e) {
            log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
        }

        consumeLoop();
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                redisService.streamConsumeMessages(
                    streamKey(),
                    groupName(),
                    consumerName,
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS,
                    this::processMessage
                );
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Consumer thread interrupted");
                    break;
                }
                log.error("Failed to consume message", e);
            }
        }
    }

    /**
     * 认领其他消费者遗留的超时未确认消息。
     * 崩溃的消费者已读取但未 ACK 的消息会滞留在消费者组的 PEL，autoClaim 把空闲超过阈值的消息
     * 转移到当前消费者后，复用与正常消费完全相同的处理路径（含幂等去重），既不丢失也不会重复执行。
     */
    private void claimPendingMessages() {
        if (!running.get()) {
            return;
        }
        try {
            Map<StreamMessageId, Map<String, String>> claimed = redisService.streamAutoClaim(
                streamKey(),
                groupName(),
                consumerName,
                Duration.ofMillis(AsyncTaskStreamConstants.CLAIM_MIN_IDLE_MS),
                AsyncTaskStreamConstants.CLAIM_BATCH_SIZE
            );
            if (claimed.isEmpty()) {
                return;
            }
            log.warn("{} consumer 认领超时未确认消息: count={}, consumerName={}",
                taskDisplayName(), claimed.size(), consumerName);
            for (Map.Entry<StreamMessageId, Map<String, String>> entry : claimed.entrySet()) {
                processMessage(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            log.warn("{} consumer 认领待确认消息失败: {}", taskDisplayName(), e.getMessage());
        }
    }

    // 包级可见而非 private：同包单元测试可直接驱动这条处理路径（幂等 / 重试 / 死信），
    // 无需启动 init() 里的消费线程。
    void processMessage(StreamMessageId messageId, Map<String, String> data) {
        T payload = parsePayload(messageId, data);
        if (payload == null) {
            ackMessage(messageId);
            return;
        }

        int retryCount = parseRetryCount(data);
        String taskId = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
        // 开启文档级并行时，消费线程只负责分发，业务在工作线程池里并行执行；
        // 否则保持历史行为，在消费线程内串行处理。
        if (workerPool != null) {
            workerPool.submit(() -> handlePayload(messageId, payload, retryCount, taskId));
        } else {
            handlePayload(messageId, payload, retryCount, taskId);
        }
    }

    private void handlePayload(StreamMessageId messageId, T payload, int retryCount, String taskId) {
        log.info("Processing {} task: payload={}, messageId={}, retryCount={}, taskId={}",
            taskDisplayName(), payloadIdentifier(payload), messageId, retryCount, taskId);

        String dedupKey = dedupKey(taskId);

        // 幂等去重：已完成的任务（重复投递 / 认领回来的旧消息）直接 ACK 丢弃，不再重复执行业务。
        if (dedupKey != null) {
            String state = redisService.getIdempotencyState(dedupKey);
            if (AsyncTaskStreamConstants.DEDUP_STATE_DONE.equals(state)) {
                log.info("{} task 已完成，跳过重复执行: {}, taskId={}",
                    taskDisplayName(), payloadIdentifier(payload), taskId);
                ackMessage(messageId);
                return;
            }
            // 抢占处理中占位失败：按当前键状态分流。
            boolean acquired = redisService.tryAcquireIdempotency(
                dedupKey, Duration.ofMillis(AsyncTaskStreamConstants.DEDUP_PROCESSING_TTL_MS));
            if (!acquired) {
                String current = redisService.getIdempotencyState(dedupKey);
                if (AsyncTaskStreamConstants.DEDUP_STATE_DONE.equals(current)) {
                    // 极小竞态窗口：首查时未完成，抢占前另一消费者恰好完成。按已完成处理，ACK 丢弃。
                    log.info("{} task 已被其他消费者完成，跳过重复执行: {}, taskId={}",
                        taskDisplayName(), payloadIdentifier(payload), taskId);
                    ackMessage(messageId);
                    return;
                }
                if (AsyncTaskStreamConstants.DEDUP_STATE_PROCESSING.equals(current)) {
                    // 另有存活消费者正在处理同一任务：让出本次执行，
                    // 不 ACK（消息留在 PEL，必要时由认领机制兜底），避免并发重复执行。
                    log.info("{} task 正在被其他消费者处理，跳过本次: {}, taskId={}",
                        taskDisplayName(), payloadIdentifier(payload), taskId);
                    return;
                }
                // 键恰在抢占与查询之间过期（持有者崩溃后 TTL 到期）：继续执行，等价于占位成功。
            }
        }

        try {
            markProcessing(payload);
            processBusiness(payload);
            markCompleted(payload);
            if (dedupKey != null) {
                redisService.markIdempotencyDone(
                    dedupKey, Duration.ofMillis(AsyncTaskStreamConstants.DEDUP_DONE_TTL_MS));
            }
            ackMessage(messageId);
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("{} task failed: {}", taskDisplayName(), payloadIdentifier(payload), e);
            // 业务失败需要重试：释放处理中占位，让重新入队的消息能再次被占用执行。
            if (dedupKey != null) {
                redisService.releaseIdempotencyIfProcessing(dedupKey);
            }
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                retryMessage(payload, retryCount + 1, taskId);
            } else {
                String error = truncateError(
                    taskDisplayName() + " failed after retry " + retryCount + ": " + e.getMessage());
                markFailed(payload, error);
                // 重试耗尽：转入死信队列，保留失败上下文，既不丢失也不再占用 PEL。
                sendToDlq(payload, taskId, error);
            }
            ackMessage(messageId);
        }
    }

    /**
     * 重试耗尽后把任务转入死信队列。子类提供原始字段，基类统一附加错误原因、时间戳与来源组。
     */
    private void sendToDlq(T payload, String taskId, String error) {
        try {
            Map<String, String> dlqMessage = new HashMap<>(buildRetryMessage(payload, 0));
            if (taskId != null) {
                dlqMessage.put(AsyncTaskStreamConstants.FIELD_TASK_ID, taskId);
            }
            dlqMessage.put(AsyncTaskStreamConstants.FIELD_DLQ_ERROR,
                error != null ? error : "unknown");
            dlqMessage.put(AsyncTaskStreamConstants.FIELD_DLQ_FAILED_AT,
                String.valueOf(System.currentTimeMillis()));
            dlqMessage.put(AsyncTaskStreamConstants.FIELD_DLQ_GROUP, groupName());
            redisService.streamAddDlq(streamKey() + AsyncTaskStreamConstants.DLQ_STREAM_SUFFIX, dlqMessage);
            log.warn("{} task 转入死信队列: {}, taskId={}",
                taskDisplayName(), payloadIdentifier(payload), taskId);
        } catch (Exception e) {
            log.error("{} task 写入死信队列失败: {}", taskDisplayName(), payloadIdentifier(payload), e);
        }
    }

    protected int parseRetryCount(Map<String, String> data) {
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 由 taskId 推导幂等去重键。taskId 为空（历史消息或未携带）时返回 null，跳过幂等保护，
     * 退化为原有"至少一次"语义，保证向后兼容。
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

    private void ackMessage(StreamMessageId messageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            log.error("Failed to ack stream message: messageId={}", messageId, e);
        }
    }

    protected RedisService redisService() {
        return redisService;
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);

    protected abstract String payloadIdentifier(T payload);

    protected abstract void markProcessing(T payload);

    protected abstract void processBusiness(T payload);

    protected abstract void markCompleted(T payload);

    protected abstract void markFailed(T payload, String error);

    /**
     * 业务失败后重新入队重试。基类默认实现复用 {@link #buildRetryMessage} 构造消息体，
     * 携带递增后的 retryCount 与原 taskId（幂等键保持不变），重新投递到原 Stream。
     * 入队失败时回退到 {@link #markFailed} 标记失败，避免任务静默丢失。
     */
    protected void retryMessage(T payload, int retryCount, String taskId) {
        try {
            Map<String, String> message = new HashMap<>(buildRetryMessage(payload, retryCount));
            if (taskId != null) {
                message.put(AsyncTaskStreamConstants.FIELD_TASK_ID, taskId);
            }
            redisService.streamAdd(streamKey(), message, AsyncTaskStreamConstants.STREAM_MAX_LEN);
            log.info("{} task 已重新入队: {}, retryCount={}, taskId={}",
                taskDisplayName(), payloadIdentifier(payload), retryCount, taskId);
        } catch (Exception e) {
            log.error("{} task 重试入队失败: {}", taskDisplayName(), payloadIdentifier(payload), e);
            markFailed(payload, truncateError("重试入队失败: " + e.getMessage()));
        }
    }

    /**
     * 构造重新入队 / 死信投递所需的原始消息字段（不含 taskId 等可靠性元数据，由基类统一附加）。
     * 子类按各自的消息结构实现；retryCount 由调用方决定，死信投递时传 0 仅占位。
     */
    protected abstract Map<String, String> buildRetryMessage(T payload, int retryCount);
}
