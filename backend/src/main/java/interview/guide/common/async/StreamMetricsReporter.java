package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis Stream 可观测性指标采集。
 * <p>
 * 周期性把四条异步管道的积压情况上报为 Micrometer Gauge，让 Prometheus 能采集、Grafana 能画图：
 * <ul>
 *   <li>{@code app.async.stream.length}：Stream 当前长度（积压消息总数）</li>
 *   <li>{@code app.async.stream.pending}：消费者组待确认数（PEL），持续偏高说明消费滞后或处理失败</li>
 *   <li>{@code app.async.stream.dlq}：死信队列长度，大于 0 说明有任务重试耗尽，需要人工介入</li>
 * </ul>
 * 三个指标都带 {@code pipeline} 标签区分管道，对应 JD 里"稳定性建设：报警、监控"的监控闭环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamMetricsReporter {

    private static final String METRIC_LENGTH = "app.async.stream.length";
    private static final String METRIC_PENDING = "app.async.stream.pending";
    private static final String METRIC_DLQ = "app.async.stream.dlq";

    private final RedisService redisService;
    private final MeterRegistry meterRegistry;

    /**
     * 一条异步管道的监控坐标：展示名 + Stream Key + 消费者组名。
     */
    private record Pipeline(String name, String streamKey, String groupName) {}

    private static final List<Pipeline> PIPELINES = List.of(
        new Pipeline("vectorize",
            AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
            AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME),
        new Pipeline("resume-analyze",
            AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
            AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME),
        new Pipeline("interview-evaluate",
            AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY,
            AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME),
        new Pipeline("voice-evaluate",
            AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY,
            AsyncTaskStreamConstants.VOICE_EVALUATE_GROUP_NAME)
    );

    // Gauge 需要绑定一个持有最新值的引用，这里用 AtomicLong 作为各管道指标的后备存储，
    // 定时任务只更新数值，Gauge 注册一次即可。
    private final ConcurrentHashMap<String, AtomicLong> gaugeValues = new ConcurrentHashMap<>();

    @PostConstruct
    public void registerGauges() {
        for (Pipeline p : PIPELINES) {
            Tags tags = Tags.of("pipeline", p.name());
            meterRegistry.gauge(METRIC_LENGTH, tags, holder(p.name() + ":length"));
            meterRegistry.gauge(METRIC_PENDING, tags, holder(p.name() + ":pending"));
            meterRegistry.gauge(METRIC_DLQ, tags, holder(p.name() + ":dlq"));
        }
        log.info("Stream 指标已注册: pipelines={}", PIPELINES.size());
    }

    private AtomicLong holder(String key) {
        return gaugeValues.computeIfAbsent(key, k -> new AtomicLong(0));
    }

    /**
     * 周期性刷新各管道的积压指标。读取失败时保持上一次的值，避免抖动成 0。
     */
    @Scheduled(fixedDelayString = "${app.async.metrics.interval-ms:15000}")
    public void refresh() {
        for (Pipeline p : PIPELINES) {
            try {
                holder(p.name() + ":length").set(redisService.streamLen(p.streamKey()));
                holder(p.name() + ":pending").set(
                    redisService.streamPendingCount(p.streamKey(), p.groupName()));
                holder(p.name() + ":dlq").set(
                    redisService.streamLen(p.streamKey() + AsyncTaskStreamConstants.DLQ_STREAM_SUFFIX));
            } catch (Exception e) {
                log.debug("刷新 Stream 指标失败: pipeline={}, error={}", p.name(), e.getMessage());
            }
        }
    }
}
