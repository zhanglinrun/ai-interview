package com.linrun.interview.modules.capability.dto;

import com.linrun.interview.modules.capability.model.JobTrack;
import java.time.LocalDate;
import java.util.List;

public record CapabilityTemplateDTO(
    String templateCode,
    JobTrack jobTrack,
    String version,
    String contentHash,
    LocalDate effectiveDate,
    List<CapabilityAtomDTO> capabilities
) {
  public CapabilityTemplateDTO {
    capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
  }
}
