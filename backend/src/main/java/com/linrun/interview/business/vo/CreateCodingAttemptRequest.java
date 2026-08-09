package com.linrun.interview.business.vo;

import com.linrun.interview.business.constant.CodingAttemptMode;
import com.linrun.interview.business.constant.CodingLanguage;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCodingAttemptRequest(
    @NotNull Long problemVersionId,
    @NotNull CodingLanguage language,
    @NotNull CodingAttemptMode mode,
    @Size(max = 80) String contextId
) {
}
