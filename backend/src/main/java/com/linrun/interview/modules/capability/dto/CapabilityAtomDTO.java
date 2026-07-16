package com.linrun.interview.modules.capability.dto;

import java.math.BigDecimal;
import java.util.List;

public record CapabilityAtomDTO(
    String atomId,
    String atomVersion,
    String name,
    String description,
    String capabilityDomain,
    String parentAtomId,
    BigDecimal defaultWeight,
    Integer minimumCoverage,
    List<String> questionTypes
) {
  public CapabilityAtomDTO {
    questionTypes = questionTypes == null ? List.of() : List.copyOf(questionTypes);
  }
}
