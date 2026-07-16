package com.linrun.interview.modules.jobinterview.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.async.AbstractRabbitTaskConsumer;
import com.linrun.interview.common.async.AbstractStreamConsumer;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PreparationRabbitConsumer extends AbstractRabbitTaskConsumer {

  private final PreparationTaskConsumer delegate;

  public PreparationRabbitConsumer(
      ObjectMapper objectMapper,
      PreparationTaskConsumer delegate
  ) {
    super(objectMapper);
    this.delegate = delegate;
  }

  @Override
  protected AbstractStreamConsumer<?> delegate() {
    return delegate;
  }

  @RabbitListener(
      queues = AsyncTaskStreamConstants.RABBIT_JOB_INTERVIEW_PREPARE_QUEUE,
      containerFactory = "taskRabbitListenerContainerFactory")
  public void onMessage(String body) {
    handle(body);
  }
}
