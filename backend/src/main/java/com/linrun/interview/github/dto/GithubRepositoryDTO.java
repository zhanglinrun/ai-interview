package com.linrun.interview.github.dto;

import com.linrun.interview.github.model.GithubRepositorySyncStatus;
import java.time.LocalDateTime;
import java.util.List;

public record GithubRepositoryDTO(
    Long id,
    String owner,
    String repository,
    String repositoryUrl,
    String defaultBranch,
    String fixedCommitSha,
    long sourceSizeKb,
    GithubRepositorySyncStatus syncStatus,
    int syncedFileCount,
    long syncedBytes,
    String syncError,
    boolean sourceAvailable,
    boolean selectionRequired,
    List<String> coreModules,
    String responsibilities,
    String keyDecisions,
    String problemsSolved,
    LocalDateTime createdAt,
    LocalDateTime lastSyncedAt
) {
  public GithubRepositoryDTO {
    coreModules = coreModules == null ? List.of() : List.copyOf(coreModules);
  }
}
