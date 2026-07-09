package com.linrun.interview.common.async;

import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * RabbitMQ 异步任务引擎装配（仅 {@code app.async.engine=rabbitmq} 时加载）。
 *
 * <p>{@code RabbitAutoConfiguration} 被 {@code spring.autoconfigure.exclude} 默认排除，避免
 * 默认 rocketmq 引擎下也去连 RabbitMQ；这里以 {@link ConditionalOnProperty} 门控后再显式
 * {@link Import} 装配 {@code ConnectionFactory / RabbitTemplate / RabbitAdmin}，与
 * {@link RocketMqEngineConfig} 的处理对称。
 *
 * <p>拓扑：一个 direct 主交换机 + 每条管道一个业务队列（声明 DLX 指向死信交换机）+ 一个死信交换机
 * + 每条管道一个死信队列。业务队列的消费失败经重试建议链重试
 * {@link AsyncTaskStreamConstants#MAX_RETRY_COUNT} 次后被拒绝（不重新入队），由 broker 经 DLX
 * 路由进对应 DLQ；{@code RabbitDlqAlarmConsumer} 订阅 DLQ 告警。
 */
@Configuration
@ConditionalOnProperty(name = "app.async.engine", havingValue = AsyncEngineProperties.ENGINE_RABBITMQ)
@Import(RabbitAutoConfiguration.class)
public class RabbitMqEngineConfig {

    @Bean
    public DirectExchange taskExchange() {
        return new DirectExchange(AsyncTaskStreamConstants.RABBIT_TASK_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange taskDlx() {
        return new DirectExchange(AsyncTaskStreamConstants.RABBIT_TASK_DLX, true, false);
    }

    // ==================== 简历分析 ====================

    @Bean
    public Queue resumeAnalyzeQueue() {
        return QueueBuilder.durable(AsyncTaskStreamConstants.RABBIT_RESUME_ANALYZE_QUEUE)
            .withArgument("x-dead-letter-exchange", AsyncTaskStreamConstants.RABBIT_TASK_DLX)
            .withArgument("x-dead-letter-routing-key",
                AsyncTaskStreamConstants.RABBIT_RESUME_ANALYZE_DLQ_ROUTING)
            .build();
    }

    @Bean
    public Queue resumeAnalyzeDlq() {
        return QueueBuilder.durable(AsyncTaskStreamConstants.RABBIT_RESUME_ANALYZE_DLQ).build();
    }

    @Bean
    public Binding resumeAnalyzeBinding() {
        return BindingBuilder.bind(resumeAnalyzeQueue()).to(taskExchange())
            .with(AsyncTaskStreamConstants.RABBIT_RESUME_ANALYZE_ROUTING);
    }

    @Bean
    public Binding resumeAnalyzeDlqBinding() {
        return BindingBuilder.bind(resumeAnalyzeDlq()).to(taskDlx())
            .with(AsyncTaskStreamConstants.RABBIT_RESUME_ANALYZE_DLQ_ROUTING);
    }

    // ==================== 面试评估 ====================

    @Bean
    public Queue interviewEvaluateQueue() {
        return QueueBuilder.durable(AsyncTaskStreamConstants.RABBIT_INTERVIEW_EVALUATE_QUEUE)
            .withArgument("x-dead-letter-exchange", AsyncTaskStreamConstants.RABBIT_TASK_DLX)
            .withArgument("x-dead-letter-routing-key",
                AsyncTaskStreamConstants.RABBIT_INTERVIEW_EVALUATE_DLQ_ROUTING)
            .build();
    }

    @Bean
    public Queue interviewEvaluateDlq() {
        return QueueBuilder.durable(AsyncTaskStreamConstants.RABBIT_INTERVIEW_EVALUATE_DLQ).build();
    }

    @Bean
    public Binding interviewEvaluateBinding() {
        return BindingBuilder.bind(interviewEvaluateQueue()).to(taskExchange())
            .with(AsyncTaskStreamConstants.RABBIT_INTERVIEW_EVALUATE_ROUTING);
    }

    @Bean
    public Binding interviewEvaluateDlqBinding() {
        return BindingBuilder.bind(interviewEvaluateDlq()).to(taskDlx())
            .with(AsyncTaskStreamConstants.RABBIT_INTERVIEW_EVALUATE_DLQ_ROUTING);
    }

    // ==================== 语音面试评估 ====================

    @Bean
    public Queue voiceEvaluateQueue() {
        return QueueBuilder.durable(AsyncTaskStreamConstants.RABBIT_VOICE_EVALUATE_QUEUE)
            .withArgument("x-dead-letter-exchange", AsyncTaskStreamConstants.RABBIT_TASK_DLX)
            .withArgument("x-dead-letter-routing-key",
                AsyncTaskStreamConstants.RABBIT_VOICE_EVALUATE_DLQ_ROUTING)
            .build();
    }

    @Bean
    public Queue voiceEvaluateDlq() {
        return QueueBuilder.durable(AsyncTaskStreamConstants.RABBIT_VOICE_EVALUATE_DLQ).build();
    }

    @Bean
    public Binding voiceEvaluateBinding() {
        return BindingBuilder.bind(voiceEvaluateQueue()).to(taskExchange())
            .with(AsyncTaskStreamConstants.RABBIT_VOICE_EVALUATE_ROUTING);
    }

    @Bean
    public Binding voiceEvaluateDlqBinding() {
        return BindingBuilder.bind(voiceEvaluateDlq()).to(taskDlx())
            .with(AsyncTaskStreamConstants.RABBIT_VOICE_EVALUATE_DLQ_ROUTING);
    }

    // ==================== 监听容器工厂（重试 → DLQ） ====================

    /**
     * 业务监听容器工厂：无状态重试 {@code MAX_RETRY_COUNT + 1} 次（含首次），指数退避；
     * 重试耗尽由 {@link RejectAndDontRequeueRecoverer} 拒绝且不重入队，经队列 DLX 进 DLQ。
     * {@code defaultRequeueRejected=false} 保证异常拒绝不会无限重入队。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory taskRabbitListenerContainerFactory(
        ConnectionFactory connectionFactory,
        SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
            .maxAttempts(AsyncTaskStreamConstants.MAX_RETRY_COUNT + 1)
            .backOffOptions(1000L, 2.0, 10000L)
            .recoverer(new RejectAndDontRequeueRecoverer())
            .build());
        return factory;
    }
}
