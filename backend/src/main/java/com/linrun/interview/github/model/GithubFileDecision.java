package com.linrun.interview.github.model;

/** 文件清单安全策略的确定性判定。 */
public record GithubFileDecision(
    GithubFileStatus status,
    GithubFileKind kind,
    String language,
    String reason,
    int priority
) {
  public boolean selectable() {
    return status == GithubFileStatus.ELIGIBLE;
  }
}
