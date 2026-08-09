package com.linrun.interview.document.constant;

public enum DocumentParseTaskStatus {
  CREATED,
  SUBMITTED,
  POLLING,
  SUCCEEDED,
  FAILED,
  FALLBACK_SUCCEEDED,
  FALLBACK_FAILED;

  public boolean isTerminal() {
    return this == SUCCEEDED || this == FALLBACK_SUCCEEDED || this == FALLBACK_FAILED;
  }
}
