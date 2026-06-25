package interview.guide.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步配置（对齐 know-engine AsyncConfig）。
 *
 * <p>启用 {@code @Async}，提供知识库向量化事件监听专用线程池 {@code eventListenerExecutor}。
 * 用 {@link ThreadPoolTaskExecutor}（非 {@code Executors.newXxx}，符合 AGENTS.md），
 * 拒绝策略 {@code CallerRunsPolicy} 背压。
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 事件监听专用线程池：处理文档切块完成后触发的向量化异步事件。
     */
    @Bean("eventListenerExecutor")
    public Executor eventListenerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("event-listener-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("事件监听线程池初始化完成: core=4, max=8, queue=50");
        return executor;
    }
}
