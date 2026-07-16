package com.linrun.interview.modules.algorithm.dto;

import com.linrun.interview.modules.algorithm.model.CodingAttemptMode;
import com.linrun.interview.modules.algorithm.model.CodingLanguage;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCodingAttemptRequest(
    @NotNull Long problemVersionId,
    @NotNull CodingLanguage language,
    @NotNull CodingAttemptMode mode,
    @Size(max = 80) String contextId
) {
}
