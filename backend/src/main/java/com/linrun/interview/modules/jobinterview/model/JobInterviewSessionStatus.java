package com.linrun.interview.modules.jobinterview.model;

public enum JobInterviewSessionStatus {
  READY,
  IN_PROGRESS,
  PAUSED,
  COMPLETING,
  COMPLETED,
  /** 兼容旧文字面试及早期岗位实战已生成报告的完成态；新流程仍写入 COMPLETED。 */
  EVALUATED,
  ABORTED,
  FAILED;

  public boolean terminal() {
    return this == COMPLETED || this == EVALUATED || this == ABORTED || this == FAILED;
  }

  public boolean completed() {
    return this == COMPLETED || this == EVALUATED;
  }

  /** 只有尚未结束且允许用户继续作答的会话才能被准备接口恢复。 */
  public boolean resumable() {
    return this == READY || this == IN_PROGRESS || this == PAUSED;
  }
}
