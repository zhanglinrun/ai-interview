package com.linrun.interview.modules.report.model;

/** 不维护覆盖式总分，只给出由最近有效证据确定性投影出的当前状态。 */
public enum CapabilityState {
  UNVERIFIED,
  WEAK,
  STABLE,
  STRENGTH,
  REVIEW
}
