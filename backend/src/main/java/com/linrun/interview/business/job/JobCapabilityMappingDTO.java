package com.linrun.interview.business.job;

import com.linrun.interview.business.job.CapabilityMappingSource;
import java.math.BigDecimal;

public record JobCapabilityMappingDTO(
    Long id,
    String atomId,
    String atomVersion,
    String capabilityName,
    CapabilityMappingSource mappingSource,
    String evidenceText,
    Integer evidenceStart,
    Integer evidenceEnd,
    BigDecimal suggestedWeight,
    BigDecimal confirmedWeight,
    BigDecimal confidence,
    boolean enabled
) {
}
