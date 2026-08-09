package com.linrun.interview.business.job;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record ConfirmJobCapabilitiesRequest(
    @NotNull @Size(min = 1, max = 20) List<@Valid CapabilityAdjustment> adjustments,
    @Valid TemporaryCapability temporaryCapability
) {
  public ConfirmJobCapabilitiesRequest {
    adjustments = adjustments == null ? List.of() : List.copyOf(adjustments);
  }

  public record CapabilityAdjustment(
      @NotNull Long mappingId,
      boolean enabled,
      @DecimalMin("0.01") @DecimalMax("1.0") BigDecimal weight
  ) {
  }

  public record TemporaryCapability(
      @Size(min = 2, max = 80) String name,
      @Size(max = 300) String description,
      @DecimalMin("0.01") @DecimalMax("1.0") BigDecimal weight
  ) {
  }
}
