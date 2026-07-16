package com.linrun.interview.modules.algorithm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitCodeRequest(
    @NotBlank @Size(max = 100) String idempotencyKey,
    @NotBlank @Size(max = 100000) String sourceCode
) {
}
