package com.linrun.interview.common.async;

import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * RabbitMQ 死信告警消费者（app.async.engine=rabbitmq 时启用）。
 *
 * <p>三条管道的消息重试耗尽后经各自 DLX 路由进对应 DLQ。本消费者订阅三个 DLQ，收到即
 * {@code log.error} 告警并上报 Prometheus 计数器 {@code app.async.dlq.total{pipeline=...}}，
 * 供 Grafana 面板 / 告警规则联动，与 RocketMQ 的 {@link InterviewEvaluateDlqAlarmConsumer} 对齐。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.async.engine", havingValue = AsyncEngineProperties.ENGINE_RABBITMQ)
public class RabbitDlqAlarmConsumer {

    private static final Map<String, String> QUEUE_TO_PIPELINE = Map.of(
        AsyncTaskStreamConstants.RABBIT_RESUME_ANALYZE_DLQ, "resume-analyze",
        AsyncTaskStreamConstants.RABBIT_INTERVIEW_EVALUATE_DLQ, "interview-evaluate",
        AsyncTaskStreamConstants.RABBIT_VOICE_EVALUATE_DLQ, "voice-evaluate"
    );

    private final MeterRegistry meterRegistry;

    public RabbitDlqAlarmConsumer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @RabbitListener(queues = {
        AsyncTaskStreamConstants.RABBIT_RESUME_ANALYZE_DLQ,
        AsyncTaskStreamConstants.RABBIT_INTERVIEW_EVALUATE_DLQ,
        AsyncTaskStreamConstants.RABBIT_VOICE_EVALUATE_DLQ
    })
    public void onDlqMessage(Message message) {
        String queue = message.getMessageProperties() != null
            ? message.getMessageProperties().getConsumerQueue() : null;
        String pipeline = QUEUE_TO_PIPELINE.getOrDefault(queue, "unknown");
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        log.error("[DLQ告警] 异步任务进入死信队列: pipeline={}, queue={}, body={}", pipeline, queue, body);
        meterRegistry.counter("app.async.dlq.total", "pipeline", pipeline).increment();
    }
}
