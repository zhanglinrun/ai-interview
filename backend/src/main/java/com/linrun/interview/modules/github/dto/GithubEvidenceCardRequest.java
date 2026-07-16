package com.linrun.interview.modules.github.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 岗位能力 × 代码证据卡输入，只使用能力目录的稳定业务键。 */
public record GithubEvidenceCardRequest(
    @NotNull @Size(min = 1, max = 20) List<@Valid CapabilityTarget> capabilities,
    @Min(1) @Max(5) Integer evidencePerCapability
) {
  public GithubEvidenceCardRequest {
    capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    evidencePerCapability = evidencePerCapability == null ? 2 : evidencePerCapability;
  }

  public record CapabilityTarget(
      @NotBlank @Size(max = 64) String atomId,
      @NotBlank @Size(max = 32) String atomVersion,
      @NotBlank @Size(max = 128) String name,
      @Size(max = 20) List<@Size(max = 64) String> keywords
  ) {
    public CapabilityTarget {
      keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }
  }
}
