package com.linrun.interview.infra.messaging;

import com.linrun.interview.infra.messaging.AsyncTaskStreamConstants;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RabbitMQ 死信告警消费者。
 *
 * <p>业务管道的消息重试耗尽后经各自 DLX 路由进对应 DLQ。本消费者收到即
 * {@code log.error} 告警并上报 Prometheus 计数器 {@code app.async.dlq.total{pipeline=...}}，
 * 供 Grafana 面板和告警规则联动。日志只记录消息元数据，不输出简历、回答等消息正文。
 */
@Slf4j
@Component
public class RabbitDlqAlarmConsumer {

    private static final Map<String, String> QUEUE_TO_PIPELINE = Map.of(
        AsyncTaskStreamConstants.RABBIT_RESUME_ANALYZE_DLQ, "resume-analyze",
        AsyncTaskStreamConstants.RABBIT_INTERVIEW_EVALUATE_DLQ, "interview-evaluate",
        AsyncTaskStreamConstants.RABBIT_JOB_INTERVIEW_PREPARE_DLQ, "job-interview-prepare",
        AsyncTaskStreamConstants.RABBIT_INTERVIEW_REPORT_DLQ, "interview-report"
    );

    private final MeterRegistry meterRegistry;

    public RabbitDlqAlarmConsumer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @RabbitListener(queues = {
        AsyncTaskStreamConstants.RABBIT_RESUME_ANALYZE_DLQ,
        AsyncTaskStreamConstants.RABBIT_INTERVIEW_EVALUATE_DLQ,
        AsyncTaskStreamConstants.RABBIT_JOB_INTERVIEW_PREPARE_DLQ,
        AsyncTaskStreamConstants.RABBIT_INTERVIEW_REPORT_DLQ
    })
    public void onDlqMessage(Message message) {
        String queue = message.getMessageProperties() != null
            ? message.getMessageProperties().getConsumerQueue() : null;
        String pipeline = QUEUE_TO_PIPELINE.getOrDefault(queue, "unknown");
        String messageId = message.getMessageProperties() != null
            ? message.getMessageProperties().getMessageId() : null;
        log.error("[DLQ告警] 异步任务进入死信队列: pipeline={}, queue={}, messageId={}",
            pipeline, queue, messageId);
        meterRegistry.counter("app.async.dlq.total", "pipeline", pipeline).increment();
    }
}
