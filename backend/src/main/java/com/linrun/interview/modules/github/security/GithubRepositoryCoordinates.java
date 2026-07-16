package com.linrun.interview.modules.github.security;

/** 经过 allowlist 校验后的 GitHub 公共仓库坐标。 */
public record GithubRepositoryCoordinates(String owner, String repository, String canonicalUrl) {
}
