package com.linrun.interview.business.vo;

import com.linrun.interview.business.constant.JobTrack;
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
