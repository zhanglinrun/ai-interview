package com.linrun.interview.github.dto;

import com.linrun.interview.github.model.GithubFileKind;
import com.linrun.interview.github.model.GithubFileStatus;

public record GithubFileCandidateDTO(
    String path,
    long byteSize,
    String language,
    GithubFileKind fileKind,
    GithubFileStatus status,
    String reason,
    boolean defaultIncluded
) {
}
