package com.linrun.interview.modules.github.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 绑定公共仓库并声明本人贡献；声明是后续中立核验的问题来源，不直接作为事实。 */
public record BindGithubRepositoryRequest(
    @NotBlank @Size(max = 300) String repositoryUrl,
    @NotNull @Valid ContributionDeclaration contribution
) {

  public record ContributionDeclaration(
      @NotNull @Size(min = 1, max = 3)
      List<@NotBlank @Size(max = 500) String> coreModules,
      @NotBlank @Size(max = 2000) String responsibilities,
      @NotBlank @Size(max = 2000) String keyDecisions,
      @NotBlank @Size(max = 2000) String problemsSolved
  ) {
    public ContributionDeclaration {
      coreModules = coreModules == null ? List.of() : List.copyOf(coreModules);
    }
  }
}
