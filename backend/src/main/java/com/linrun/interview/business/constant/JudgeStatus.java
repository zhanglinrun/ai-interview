package com.linrun.interview.business.constant;

/** 客观执行状态；UNAVAILABLE 表示未执行，不能解释为失败或通过。 */
public enum JudgeStatus {
  QUEUED,
  PROCESSING,
  ACCEPTED,
  WRONG_ANSWER,
  COMPILE_ERROR,
  RUNTIME_ERROR,
  TIME_LIMIT_EXCEEDED,
  MEMORY_LIMIT_EXCEEDED,
  INTERNAL_ERROR,
  UNAVAILABLE;

  public boolean terminal() {
    return this != QUEUED && this != PROCESSING;
  }

  public boolean pendingRejudge() {
    return this == UNAVAILABLE || this == INTERNAL_ERROR;
  }
}
