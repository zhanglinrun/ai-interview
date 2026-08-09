package com.linrun.interview.github.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 同步选择。includePaths 为空时使用清单中的默认选择；excludePrefixes 可排除不相关模块。
 */
public record SyncGithubRepositoryRequest(
    @NotBlank @Pattern(regexp = "[a-fA-F0-9]{40}") String expectedCommitSha,
    @Size(max = 1000) List<@Size(max = 500) String> includePaths,
    @Size(max = 100) List<@Size(max = 500) String> excludePrefixes
) {
  public SyncGithubRepositoryRequest {
    includePaths = includePaths == null ? List.of() : List.copyOf(includePaths);
    excludePrefixes = excludePrefixes == null ? List.of() : List.copyOf(excludePrefixes);
  }
}
