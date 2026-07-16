package com.linrun.interview.modules.jobinterview.model;

import java.time.Duration;

/** 岗位实战固定四阶段及服务端软时间预算。 */
public enum JobInterviewStage {
  PROJECT_DEEP_DIVE(Duration.ofMinutes(12)),
  POSITION_TECH(Duration.ofMinutes(12)),
  ENGINEERING_SCENARIO(Duration.ofMinutes(6)),
  ALGORITHM(Duration.ofMinutes(15));

  private final Duration budget;

  JobInterviewStage(Duration budget) {
    this.budget = budget;
  }

  public Duration budget() {
    return budget;
  }

  public JobInterviewStage next() {
    int next = ordinal() + 1;
    return next < values().length ? values()[next] : null;
  }
}
