package com.linrun.interview.modules.github.dto;

import com.linrun.interview.modules.github.model.GithubRepositorySyncStatus;

public record GithubSyncResultDTO(
    Long repositoryId,
    String commitSha,
    GithubRepositorySyncStatus status,
    int syncedFiles,
    long syncedBytes,
    int evidenceChunks,
    int blockedFiles,
    boolean reusedSnapshot
) {
}
