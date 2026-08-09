package com.linrun.interview.business.service;

public enum AgentRunStatus {
  RUNNING,
  COMPLETED,
  DEGRADED,
  FAILED;

  public boolean terminal() {
    return this != RUNNING;
  }
}
