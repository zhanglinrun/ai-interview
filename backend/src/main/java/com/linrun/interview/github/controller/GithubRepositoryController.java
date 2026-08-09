package com.linrun.interview.github.controller;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.github.dto.BindGithubRepositoryRequest;
import com.linrun.interview.github.dto.GithubEvidenceCardDTO;
import com.linrun.interview.github.dto.GithubEvidenceCardRequest;
import com.linrun.interview.github.dto.GithubRepositoryDTO;
import com.linrun.interview.github.dto.GithubRepositoryDetailDTO;
import com.linrun.interview.github.dto.GithubSyncResultDTO;
import com.linrun.interview.github.dto.SyncGithubRepositoryRequest;
import com.linrun.interview.github.service.GithubEvidenceCardService;
import com.linrun.interview.github.service.GithubRepositoryService;
import com.linrun.interview.github.service.GithubRepositorySyncService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GitHub 公共仓库固定 SHA 证据 API。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/github/repositories")
@Tag(name = "GitHub 代码证据", description = "公共仓库绑定、受限同步和岗位能力证据卡")
public class GithubRepositoryController {

  private final GithubRepositoryService repositoryService;
  private final GithubRepositorySyncService syncService;
  private final GithubEvidenceCardService evidenceCardService;

  @PostMapping
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<GithubRepositoryDetailDTO> bind(
      @Valid @RequestBody BindGithubRepositoryRequest request
  ) {
    return Result.success(repositoryService.bind(UserContext.requireUserId(), request));
  }

  @GetMapping
  public Result<List<GithubRepositoryDTO>> list() {
    return Result.success(repositoryService.list(UserContext.requireUserId()));
  }

  @GetMapping("/{repositoryId}")
  public Result<GithubRepositoryDetailDTO> detail(@PathVariable Long repositoryId) {
    return Result.success(repositoryService.detail(UserContext.requireUserId(), repositoryId));
  }

  @PostMapping("/{repositoryId}/sync")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
  public Result<GithubSyncResultDTO> sync(
      @PathVariable Long repositoryId,
      @Valid @RequestBody SyncGithubRepositoryRequest request
  ) {
    return Result.success(syncService.sync(UserContext.requireUserId(), repositoryId, request));
  }

  @PostMapping("/{repositoryId}/evidence-cards")
  public Result<List<GithubEvidenceCardDTO>> evidenceCards(
      @PathVariable Long repositoryId,
      @Valid @RequestBody GithubEvidenceCardRequest request
  ) {
    return Result.success(evidenceCardService.generate(
        UserContext.requireUserId(), repositoryId, request));
  }

  @DeleteMapping("/{repositoryId}")
  public Result<Void> delete(@PathVariable Long repositoryId) {
    repositoryService.delete(UserContext.requireUserId(), repositoryId);
    return Result.success(null);
  }
}
