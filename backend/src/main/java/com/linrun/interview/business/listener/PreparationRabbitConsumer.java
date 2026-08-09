package com.linrun.interview.business.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.infra.messaging.AbstractRabbitTaskConsumer;
import com.linrun.interview.infra.messaging.AbstractStreamConsumer;
import com.linrun.interview.infra.messaging.AsyncTaskStreamConstants;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
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
  public void onMessage(Message message) {
    handle(message);
  }
}
