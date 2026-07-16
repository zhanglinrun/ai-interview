package com.linrun.interview.modules.github.model;

/** 文件在安全清单和固定 SHA 快照中的状态。 */
public enum GithubFileStatus {
  ELIGIBLE,
  USER_EXCLUDED,
  EXCLUDED_INVALID_PATH,
  EXCLUDED_SENSITIVE_PATH,
  EXCLUDED_BINARY,
  EXCLUDED_DEPENDENCY,
  EXCLUDED_BUILD_OUTPUT,
  EXCLUDED_GENERATED,
  EXCLUDED_UNSUPPORTED,
  EXCLUDED_TOO_LARGE,
  SYNCED,
  SECRET_BLOCKED,
  FETCH_FAILED;

  public boolean selectable() {
    return this == ELIGIBLE || this == USER_EXCLUDED || this == SYNCED || this == FETCH_FAILED;
  }
}
