package com.linrun.interview.rag.model;

/** 证据充分性结论。技术能力评分必须与该结论分开。 */
public enum EvidenceStatus {
  SUFFICIENT,
  WEAK,
  NONE,
  CONFLICT
}
