package com.linrun.interview.business.job;

import com.linrun.interview.business.constant.JobTrack;
import com.linrun.interview.business.job.JobDescriptionStatus;
import java.time.LocalDateTime;
import java.util.List;

public record JobDescriptionDTO(
    Long id,
    String targetKey,
    Integer version,
    String title,
    String company,
    JobTrack jobTrack,
    String jdText,
    String sourceUrl,
    String contentHash,
    JobDescriptionStatus status,
    String templateCode,
    String templateVersion,
    LocalDateTime frozenAt,
    LocalDateTime createdAt,
    List<JobCapabilityMappingDTO> capabilities
) {
  public JobDescriptionDTO {
    capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
  }
}
