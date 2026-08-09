package com.linrun.interview.business.job;

import com.linrun.interview.business.constant.JobTrack;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateJobDescriptionRequest(
    @NotBlank @Size(max = 120) String title,
    @Size(max = 120) String company,
    @NotNull JobTrack jobTrack,
    @NotBlank @Size(min = 50, max = 30000) String jdText,
    @Size(max = 1000) String sourceUrl
) {
}
