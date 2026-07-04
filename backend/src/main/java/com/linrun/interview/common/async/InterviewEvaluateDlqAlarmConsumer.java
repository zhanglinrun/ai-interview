package com.linrun.interview.common.async;

import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 面试评估死信告警消费者（默认关闭，opt-in）。
 *
 * <p>面试评估消息在 broker 重试耗尽后被路由到 {@code %DLQ%evaluate-group} 死信 topic。
 * 本消费者订阅该死信 topic，收到即 {@code log.error} 告警并上报 Prometheus 计数器
 * {@code app.async.dlq.total{pipeline="interview-evaluate"}}，供 Grafana 面板 / 告警规则联动。
 *
 * <p>启用前置条件（RocketMQ 死信 topic 默认仅有写权限，需手动开读）：
 * <ol>
 *   <li>{@code app.async.engine=rocketmq} 且 RocketMQ 已连通；</li>
 *   <li>为死信 topic 开读权限：{@code mqadmin updateTopic -t %DLQ%evaluate-group -p 6 -n <nameserver>}；</li>
 *   <li>{@code app.async.rocketmq.dlq-monitor.enabled=true}。</li>
 * </ol>
 * 默认关闭以避免死信 topic 未开读权限时刷 route-not-found 告警。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.async.rocketmq.dlq-monitor.enabled", havingValue = "true")
@RocketMQMessageListener(
    topic = AsyncTaskStreamConstants.ROCKETMQ_DLQ_TOPIC_PREFIX
        + AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME,
    consumerGroup = "interview-evaluate-dlq-monitor")
public class InterviewEvaluateDlqAlarmConsumer implements RocketMQListener<MessageExt> {

    private static final String PIPELINE = "interview-evaluate";

    private final MeterRegistry meterRegistry;

    public InterviewEvaluateDlqAlarmConsumer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void onMessage(MessageExt message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        log.error("[DLQ告警] 面试评估任务进入死信队列: msgId={}, reconsumeTimes={}, body={}",
            message.getMsgId(), message.getReconsumeTimes(), body);
        meterRegistry.counter("app.async.dlq.total", "pipeline", PIPELINE).increment();
    }
}
