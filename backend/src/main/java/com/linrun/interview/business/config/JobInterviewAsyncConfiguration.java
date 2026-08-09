package com.linrun.interview.business.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.linrun.interview.infra.observability.TraceTaskDecorator;

@Configuration
public class JobInterviewAsyncConfiguration {

  /** 单机 4C6G 下只允许少量并发生成，队列有界并显式背压。 */
  @Bean("jobInterviewExecutor")
  public Executor jobInterviewExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(3);
    executor.setQueueCapacity(20);
    executor.setThreadNamePrefix("job-interview-");
    executor.setTaskDecorator(new TraceTaskDecorator());
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
  }
}
