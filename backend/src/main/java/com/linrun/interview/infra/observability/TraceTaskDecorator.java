package com.linrun.interview.infra.observability;

import org.springframework.core.task.TaskDecorator;

/** Propagates the request trace into Spring-managed executor tasks. */
public final class TraceTaskDecorator implements TaskDecorator {

  @Override
  public Runnable decorate(Runnable runnable) {
    return TraceContext.wrap(runnable);
  }
}
