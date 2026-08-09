package com.linrun.interview.github.dto;

import com.linrun.interview.github.model.GithubRepositorySyncStatus;

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
