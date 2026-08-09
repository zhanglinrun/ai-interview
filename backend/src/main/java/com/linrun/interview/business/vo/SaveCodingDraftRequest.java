package com.linrun.interview.business.vo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record SaveCodingDraftRequest(
    @NotNull @PositiveOrZero Integer expectedRevision,
    @NotNull @Size(max = 100000) String sourceCode
) {
}
