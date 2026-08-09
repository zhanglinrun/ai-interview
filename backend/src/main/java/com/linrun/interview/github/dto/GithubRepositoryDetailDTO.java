package com.linrun.interview.github.dto;

import java.util.List;

public record GithubRepositoryDetailDTO(
    GithubRepositoryDTO repository,
    int eligibleFileCount,
    long eligibleBytes,
    List<GithubFileCandidateDTO> files
) {
  public GithubRepositoryDetailDTO {
    files = files == null ? List.of() : List.copyOf(files);
  }
}
