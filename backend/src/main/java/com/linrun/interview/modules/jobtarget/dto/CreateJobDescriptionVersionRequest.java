package com.linrun.interview.modules.jobtarget.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateJobDescriptionVersionRequest(
    @NotBlank @Size(min = 50, max = 30000) String jdText,
    @Size(max = 1000) String sourceUrl
) {
}
