package com.linrun.interview.business.job;

import java.util.List;

public record JdAnalysisResultDTO(
    Long jobDescriptionId,
    boolean fallbackUsed,
    String warning,
    List<JobCapabilityMappingDTO> capabilities
) {
  public JdAnalysisResultDTO {
    capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
  }
}
