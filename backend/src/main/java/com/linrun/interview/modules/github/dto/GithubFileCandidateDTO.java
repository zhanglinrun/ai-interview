package com.linrun.interview.modules.github.dto;

import com.linrun.interview.modules.github.model.GithubFileKind;
import com.linrun.interview.modules.github.model.GithubFileStatus;

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
