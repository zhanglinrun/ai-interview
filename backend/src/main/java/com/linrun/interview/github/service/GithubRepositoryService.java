package com.linrun.interview.github.service;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.github.client.GithubPublicApiClient;
import com.linrun.interview.github.client.GithubPublicApiClient.RepositoryDescriptor;
import com.linrun.interview.github.client.GithubPublicApiClient.RepositoryTree;
import com.linrun.interview.github.config.GithubEvidenceProperties;
import com.linrun.interview.github.dto.BindGithubRepositoryRequest;
import com.linrun.interview.github.dto.GithubFileCandidateDTO;
import com.linrun.interview.github.dto.GithubRepositoryDTO;
import com.linrun.interview.github.dto.GithubRepositoryDetailDTO;
import com.linrun.interview.github.model.GithubFileDecision;
import com.linrun.interview.github.model.GithubFileKind;
import com.linrun.interview.github.model.GithubFileStatus;
import com.linrun.interview.github.model.GithubRepositoryEntity;
import com.linrun.interview.github.model.GithubRepositoryFileEntity;
import com.linrun.interview.github.security.GithubFilePolicy;
import com.linrun.interview.github.security.GithubPathPolicy;
import com.linrun.interview.github.security.GithubRepositoryCoordinates;
import com.linrun.interview.github.security.GithubRepositoryUrlPolicy;
import com.linrun.interview.rag.service.EvidenceSnapshotService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 公开仓库绑定、固定 SHA 清单预览和用户隔离查询。 */
@Service
public class GithubRepositoryService {

  private final GithubEvidenceProperties properties;
  private final GithubRepositoryUrlPolicy urlPolicy;
  private final GithubPublicApiClient apiClient;
  private final GithubFilePolicy filePolicy;
  private final GithubRepositoryPersistenceService persistenceService;
  private final GithubEvidenceIndexer evidenceIndexer;
  private final EvidenceSnapshotService evidenceSnapshotService;

  public GithubRepositoryService(
      GithubEvidenceProperties properties,
      GithubRepositoryUrlPolicy urlPolicy,
      GithubPublicApiClient apiClient,
      GithubFilePolicy filePolicy,
      GithubRepositoryPersistenceService persistenceService,
      GithubEvidenceIndexer evidenceIndexer,
      EvidenceSnapshotService evidenceSnapshotService
  ) {
    this.properties = properties;
    this.urlPolicy = urlPolicy;
    this.apiClient = apiClient;
    this.filePolicy = filePolicy;
    this.persistenceService = persistenceService;
    this.evidenceIndexer = evidenceIndexer;
    this.evidenceSnapshotService = evidenceSnapshotService;
  }

  /**
   * 外部调用先完成，随后进入短事务保存绑定和清单；不会在数据库事务内调用 GitHub。
   */
  public GithubRepositoryDetailDTO bind(Long userId, BindGithubRepositoryRequest request) {
    ensureEnabled();
    GithubRepositoryCoordinates coordinates = urlPolicy.parse(request.repositoryUrl());
    List<String> coreModules = normalizeCoreModules(request.contribution().coreModules());

    RepositoryDescriptor descriptor = apiClient.getPublicRepository(
        coordinates.owner(), coordinates.repository());
    if (descriptor.privateRepository()) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "V1 不支持私有 GitHub 仓库");
    }
    if (descriptor.defaultBranch() == null || descriptor.defaultBranch().isBlank()) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub 仓库没有默认分支");
    }
    String commitSha = apiClient.resolveCommitSha(
        coordinates.owner(), coordinates.repository(), descriptor.defaultBranch());
    RepositoryTree tree = apiClient.getTree(
        coordinates.owner(), coordinates.repository(), commitSha);
    if (tree.truncated()) {
      throw new BusinessException(ErrorCode.GITHUB_SYNC_LIMIT_EXCEEDED,
          "GitHub 返回的仓库 Tree 被截断，请绑定更小的公共仓库");
    }
    if (tree.entries().size() > properties.getMaxTreeEntries()) {
      throw new BusinessException(ErrorCode.GITHUB_SYNC_LIMIT_EXCEEDED,
          "仓库文件清单超过安全上限，请选择更小的项目仓库");
    }

    List<ManifestCandidate> candidates = tree.entries().stream()
        .filter(GithubPublicApiClient.TreeEntry::isBlob)
        .map(entry -> new ManifestCandidate(entry, filePolicy.classify(entry)))
        .sorted(manifestComparator(coreModules))
        .toList();
    List<GithubRepositoryFileEntity> manifest = buildManifest(commitSha, candidates, coreModules);
    GithubRepositoryEntity repository = GithubRepositoryEntity.builder()
        .userId(userId)
        .ownerName(coordinates.owner())
        .repositoryName(coordinates.repository())
        .repositoryUrl(coordinates.canonicalUrl())
        .defaultBranch(descriptor.defaultBranch())
        .fixedCommitSha(commitSha)
        .sourceSizeKb(descriptor.sizeKb())
        .build();
    repository = persistenceService.createOrRefreshBinding(
        repository,
        new BindGithubRepositoryRequest.ContributionDeclaration(
            coreModules,
            request.contribution().responsibilities(),
            request.contribution().keyDecisions(),
            request.contribution().problemsSolved()),
        manifest);
    return detail(userId, repository.getId());
  }

  public List<GithubRepositoryDTO> list(Long userId) {
    return persistenceService.listRepositories(userId).stream()
        .map(repository -> toDto(repository, persistenceService.listFiles(userId, repository.getId())))
        .toList();
  }

  public GithubRepositoryDetailDTO detail(Long userId, Long repositoryId) {
    GithubRepositoryEntity repository = persistenceService.requireRepository(userId, repositoryId);
    List<GithubRepositoryFileEntity> files = persistenceService.listFiles(userId, repositoryId);
    int eligibleCount = 0;
    long eligibleBytes = 0L;
    List<GithubFileCandidateDTO> fileDtos = new ArrayList<>(files.size());
    for (GithubRepositoryFileEntity file : files) {
      if (file.getStatus().selectable()) {
        eligibleCount++;
        eligibleBytes += value(file.getByteSize());
      }
      fileDtos.add(toFileDto(file));
    }
    return new GithubRepositoryDetailDTO(
        toDto(repository, files), eligibleCount, eligibleBytes, fileDtos);
  }

  public GithubRepositoryEntity require(Long userId, Long repositoryId) {
    return persistenceService.requireRepository(userId, repositoryId);
  }

  public void delete(Long userId, Long repositoryId) {
    GithubRepositoryEntity repository = persistenceService.requireRepository(userId, repositoryId);
    evidenceIndexer.delete(repository);
    evidenceSnapshotService.markSourceUnavailable(
        userId, DataDomain.GITHUB, "github-repository:" + repositoryId,
        repository.getFixedCommitSha());
    persistenceService.deleteRepository(userId, repositoryId);
  }

  private List<GithubRepositoryFileEntity> buildManifest(
      String commitSha,
      List<ManifestCandidate> candidates,
      List<String> coreModules
  ) {
    int selectedCount = 0;
    long selectedBytes = 0L;
    int selectableByBudget = Math.min(properties.getMaxFiles(), properties.getRequestBudget());
    List<GithubRepositoryFileEntity> result = new ArrayList<>(candidates.size());
    for (ManifestCandidate candidate : candidates) {
      GithubFileDecision decision = candidate.decision();
      boolean relevant = isCoreModule(candidate.entry().path(), coreModules)
          || decision.kind() == GithubFileKind.README
          || decision.kind() == GithubFileKind.BUILD
          || decision.kind() == GithubFileKind.CI;
      boolean fits = selectedCount < selectableByBudget
          && selectedBytes + candidate.entry().size() <= properties.getMaxBytes();
      boolean defaultIncluded = decision.selectable() && relevant && fits;
      if (defaultIncluded) {
        selectedCount++;
        selectedBytes += candidate.entry().size();
      }
      result.add(GithubRepositoryFileEntity.builder()
          .commitSha(commitSha)
          .path(candidate.entry().path())
          .blobSha(candidate.entry().sha())
          .byteSize(candidate.entry().size())
          .language(decision.language())
          .fileKind(decision.kind())
          .status(decision.status())
          .statusReason(decision.reason())
          .defaultIncluded(defaultIncluded)
          .build());
    }
    return result;
  }

  private Comparator<ManifestCandidate> manifestComparator(List<String> coreModules) {
    return Comparator
        .comparing((ManifestCandidate candidate) ->
            isCoreModule(candidate.entry().path(), coreModules) ? 0 : 1)
        .thenComparing((ManifestCandidate candidate) -> -candidate.decision().priority())
        .thenComparing(candidate -> candidate.entry().path().toLowerCase(Locale.ROOT));
  }

  private List<String> normalizeCoreModules(List<String> rawModules) {
    Set<String> unique = new HashSet<>();
    List<String> normalized = new ArrayList<>();
    for (String raw : rawModules) {
      String module = raw == null ? "" : raw.strip().replaceAll("/+$", "");
      if (!GithubPathPolicy.isSafe(module)) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "核心模块必须是安全的仓库相对路径: " + module);
      }
      String key = module.toLowerCase(Locale.ROOT);
      if (unique.add(key)) {
        normalized.add(module);
      }
    }
    if (normalized.isEmpty() || normalized.size() > 3) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请声明 1～3 个核心模块");
    }
    return List.copyOf(normalized);
  }

  private boolean isCoreModule(String path, List<String> modules) {
    return modules.stream().anyMatch(module -> GithubPathPolicy.isWithin(path, module));
  }

  private GithubRepositoryDTO toDto(
      GithubRepositoryEntity repository,
      List<GithubRepositoryFileEntity> files
  ) {
    long selectableCount = files.stream().filter(file -> file.getStatus().selectable()).count();
    long selectedCount = files.stream().filter(file -> Boolean.TRUE.equals(file.getDefaultIncluded())).count();
    long selectableBytes = files.stream().filter(file -> file.getStatus().selectable())
        .mapToLong(file -> value(file.getByteSize())).sum();
    boolean selectionRequired = selectableCount > selectedCount
        || selectableBytes > properties.getMaxBytes();
    return new GithubRepositoryDTO(
        repository.getId(),
        repository.getOwnerName(),
        repository.getRepositoryName(),
        repository.getRepositoryUrl(),
        repository.getDefaultBranch(),
        repository.getFixedCommitSha(),
        value(repository.getSourceSizeKb()),
        repository.getSyncStatus(),
        intValue(repository.getSyncedFileCount()),
        value(repository.getSyncedBytes()),
        repository.getSyncError(),
        !Boolean.FALSE.equals(repository.getSourceAvailable()),
        selectionRequired,
        persistenceService.parseCoreModules(repository),
        repository.getResponsibilities(),
        repository.getKeyDecisions(),
        repository.getProblemsSolved(),
        repository.getCreatedAt(),
        repository.getLastSyncedAt());
  }

  private GithubFileCandidateDTO toFileDto(GithubRepositoryFileEntity file) {
    return new GithubFileCandidateDTO(
        file.getPath(),
        value(file.getByteSize()),
        file.getLanguage(),
        file.getFileKind(),
        file.getStatus(),
        file.getStatusReason(),
        Boolean.TRUE.equals(file.getDefaultIncluded()));
  }

  private void ensureEnabled() {
    if (!properties.isEnabled()) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub 代码证据功能未启用");
    }
  }

  private long value(Long value) {
    return value == null ? 0L : value;
  }

  private int intValue(Integer value) {
    return value == null ? 0 : value;
  }

  private record ManifestCandidate(
      GithubPublicApiClient.TreeEntry entry,
      GithubFileDecision decision
  ) {
  }
}
