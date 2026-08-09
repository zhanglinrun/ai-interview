package com.linrun.interview.github.service;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.github.model.CodeChunk;
import com.linrun.interview.github.service.GithubCodeChunker;
import com.linrun.interview.github.client.GithubPublicApiClient;
import com.linrun.interview.github.client.GithubPublicApiClient.BlobContent;
import com.linrun.interview.github.config.GithubEvidenceProperties;
import com.linrun.interview.github.dto.GithubSyncResultDTO;
import com.linrun.interview.github.dto.SyncGithubRepositoryRequest;
import com.linrun.interview.github.model.GithubCodeEvidenceEntity;
import com.linrun.interview.github.model.GithubFileStatus;
import com.linrun.interview.github.model.GithubRepositoryEntity;
import com.linrun.interview.github.model.GithubRepositoryFileEntity;
import com.linrun.interview.github.model.GithubRepositorySyncStatus;
import com.linrun.interview.github.security.GithubContentInspector;
import com.linrun.interview.github.security.GithubContentInspector.InspectionResult;
import com.linrun.interview.github.security.GithubPathPolicy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 固定 Commit SHA 的受限文件同步、内容安全检查、代码切块与统一 RAG 索引。 */
@Slf4j
@Service
public class GithubRepositorySyncService {

  private final GithubEvidenceProperties properties;
  private final GithubPublicApiClient apiClient;
  private final GithubContentInspector contentInspector;
  private final GithubCodeChunker codeChunker;
  private final GithubRepositoryPersistenceService persistenceService;
  private final GithubEvidenceIndexer evidenceIndexer;

  public GithubRepositorySyncService(
      GithubEvidenceProperties properties,
      GithubPublicApiClient apiClient,
      GithubContentInspector contentInspector,
      GithubCodeChunker codeChunker,
      GithubRepositoryPersistenceService persistenceService,
      GithubEvidenceIndexer evidenceIndexer
  ) {
    this.properties = properties;
    this.apiClient = apiClient;
    this.contentInspector = contentInspector;
    this.codeChunker = codeChunker;
    this.persistenceService = persistenceService;
    this.evidenceIndexer = evidenceIndexer;
  }

  public GithubSyncResultDTO sync(
      Long userId,
      Long repositoryId,
      SyncGithubRepositoryRequest request
  ) {
    if (!properties.isEnabled()) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub 代码证据功能未启用");
    }
    GithubRepositoryEntity repository = persistenceService.requireRepository(userId, repositoryId);
    if (!repository.getFixedCommitSha().equalsIgnoreCase(request.expectedCommitSha())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "页面中的 Commit SHA 已过期，请刷新仓库清单后重试");
    }
    List<GithubRepositoryFileEntity> manifest = persistenceService.listFiles(userId, repositoryId);
    List<GithubRepositoryFileEntity> selected = selectFiles(manifest, request);
    validateLimits(selected);
    String fingerprint = fingerprint(repository.getFixedCommitSha(), selected);

    if (repository.getSyncStatus() == GithubRepositorySyncStatus.SYNCED
        && fingerprint.equals(repository.getSyncFingerprint())) {
      int chunks = persistenceService.countCompletelyIndexedEvidence(
          userId, repositoryId, repository.getFixedCommitSha());
      if (chunks > 0) {
        return new GithubSyncResultDTO(
            repositoryId,
            repository.getFixedCommitSha(),
            repository.getSyncStatus(),
            repository.getSyncedFileCount(),
            repository.getSyncedBytes(),
            chunks,
            0,
            true);
      }
    }

    persistenceService.claimSync(userId, repositoryId);
    try {
      return performSync(repository, manifest, selected, fingerprint);
    } catch (BusinessException e) {
      GithubRepositorySyncStatus failureStatus =
          e.getCode().equals(ErrorCode.GITHUB_REPOSITORY_NOT_FOUND.getCode())
              ? GithubRepositorySyncStatus.SOURCE_UNAVAILABLE
              : GithubRepositorySyncStatus.FAILED;
      persistenceService.markSyncFailure(
          userId, repositoryId, failureStatus, safeSyncError(e, failureStatus));
      throw e;
    } catch (Exception e) {
      persistenceService.markSyncFailure(
          userId, repositoryId, GithubRepositorySyncStatus.FAILED,
          "GitHub 固定快照同步失败，请稍后重试");
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE,
          "GitHub 固定快照同步失败，请稍后重试", e);
    }
  }

  private GithubSyncResultDTO performSync(
      GithubRepositoryEntity repository,
      List<GithubRepositoryFileEntity> manifest,
      List<GithubRepositoryFileEntity> selected,
      String fingerprint
  ) {
    Set<String> selectedPaths = selected.stream()
        .map(GithubRepositoryFileEntity::getPath)
        .collect(java.util.stream.Collectors.toSet());
    LocalDateTime now = LocalDateTime.now();
    for (GithubRepositoryFileEntity file : manifest) {
      file.setUpdatedAt(now);
      file.setRepositoryId(repository.getId());
      file.setUserId(repository.getUserId());
      if (file.getStatus().selectable() && !selectedPaths.contains(file.getPath())) {
        file.setStatus(GithubFileStatus.USER_EXCLUDED);
        file.setStatusReason("用户未选择该文件");
        file.setDefaultIncluded(false);
        file.setContentHash(null);
        file.setContentSnapshot(null);
      }
    }

    List<GithubCodeEvidenceEntity> evidence = new ArrayList<>();
    int syncedFiles = 0;
    int blockedFiles = 0;
    long syncedBytes = 0L;
    for (GithubRepositoryFileEntity file : selected) {
      BlobContent blob = apiClient.getBlob(
          repository.getOwnerName(),
          repository.getRepositoryName(),
          file.getBlobSha(),
          properties.getMaxFileBytes());
      if (!blob.sha().equalsIgnoreCase(file.getBlobSha())) {
        throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE,
            "GitHub 文件快照 SHA 校验失败");
      }
      InspectionResult inspection = contentInspector.inspect(file.getPath(), blob.bytes());
      if (!inspection.accepted()) {
        blockedFiles++;
        file.setStatus(inspection.rejectionStatus());
        file.setStatusReason(inspection.reason());
        file.setDefaultIncluded(false);
        file.setContentHash(null);
        file.setContentSnapshot(null);
        continue;
      }

      String content = inspection.content();
      String fileHash = GithubHashing.sha256(content);
      List<CodeChunk> chunks = codeChunker.chunk(file.getPath(), file.getLanguage(), content);
      if (chunks.size() > properties.getMaxChunksPerFile()) {
        chunks = chunks.subList(0, properties.getMaxChunksPerFile());
      }
      if (evidence.size() + chunks.size() > properties.getMaxEvidenceChunks()) {
        throw new BusinessException(ErrorCode.GITHUB_SYNC_LIMIT_EXCEEDED,
            "代码切块数量超过安全上限，请缩小同步范围");
      }
      String parentSummary = codeChunker.summarizeFile(
          file.getPath(), file.getLanguage(), chunks);
      for (CodeChunk chunk : chunks) {
        evidence.add(toEvidence(repository, file, chunk, parentSummary, now));
      }
      file.setStatus(GithubFileStatus.SYNCED);
      file.setStatusReason(null);
      file.setDefaultIncluded(true);
      file.setContentHash(fileHash);
      file.setContentSnapshot(content);
      syncedFiles++;
      syncedBytes += blob.bytes().length;
    }

    persistenceService.replaceSnapshot(
        repository,
        manifest,
        evidence,
        fingerprint,
        syncedFiles,
        syncedBytes,
        blockedFiles);

    GithubRepositorySyncStatus resultStatus = blockedFiles > 0
        ? GithubRepositorySyncStatus.PARTIAL : GithubRepositorySyncStatus.SYNCED;
    try {
      List<String> embeddingIds = evidenceIndexer.replace(repository, evidence);
      persistenceService.updateEmbeddingIds(evidence, embeddingIds);
    } catch (Exception e) {
      resultStatus = GithubRepositorySyncStatus.PARTIAL;
      log.error(
          "GitHub 固定 SHA 快照已落库，但统一向量索引失败，将保留 PARTIAL 状态并允许重试: "
              + "userId={}, repositoryId={}, commitSha={}, evidenceChunks={}",
          repository.getUserId(), repository.getId(), repository.getFixedCommitSha(),
          evidence.size(), e);
      persistenceService.markSyncFailure(
          repository.getUserId(), repository.getId(), resultStatus,
          "固定 SHA 快照已保存，但向量索引暂不可用；可稍后重试同步");
    }
    return new GithubSyncResultDTO(
        repository.getId(),
        repository.getFixedCommitSha(),
        resultStatus,
        syncedFiles,
        syncedBytes,
        evidence.size(),
        blockedFiles,
        false);
  }

  private List<GithubRepositoryFileEntity> selectFiles(
      List<GithubRepositoryFileEntity> manifest,
      SyncGithubRepositoryRequest request
  ) {
    Set<String> explicit = normalizePaths(request.includePaths(), "includePaths");
    Set<String> excludes = normalizePaths(request.excludePrefixes(), "excludePrefixes");
    Set<String> knownPaths = manifest.stream()
        .map(GithubRepositoryFileEntity::getPath)
        .collect(java.util.stream.Collectors.toSet());
    if (!knownPaths.containsAll(explicit)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "includePaths 包含清单外文件");
    }

    List<GithubRepositoryFileEntity> selected = manifest.stream()
        .filter(file -> file.getStatus().selectable())
        .filter(file -> explicit.isEmpty()
            ? Boolean.TRUE.equals(file.getDefaultIncluded())
            : explicit.contains(file.getPath()))
        .filter(file -> excludes.stream().noneMatch(prefix ->
            GithubPathPolicy.isWithin(file.getPath(), prefix)))
        .sorted(Comparator.comparing(GithubRepositoryFileEntity::getPath))
        .toList();
    if (selected.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "至少选择一个可同步的安全文本文件");
    }
    return selected;
  }

  private Set<String> normalizePaths(List<String> paths, String field) {
    Set<String> normalized = new LinkedHashSet<>();
    for (String raw : paths) {
      String path = raw == null ? "" : raw.strip().replaceAll("/+$", "");
      if (!GithubPathPolicy.isSafe(path)) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 包含非法仓库路径");
      }
      normalized.add(path);
    }
    return normalized;
  }

  private void validateLimits(List<GithubRepositoryFileEntity> selected) {
    if (selected.size() > properties.getMaxFiles()) {
      throw new BusinessException(ErrorCode.GITHUB_SYNC_LIMIT_EXCEEDED,
          "选择文件数超过上限 " + properties.getMaxFiles());
    }
    if (selected.size() > properties.getRequestBudget()) {
      throw new BusinessException(ErrorCode.GITHUB_SYNC_LIMIT_EXCEEDED,
          "选择文件数超过本次 GitHub API 请求预算");
    }
    long totalBytes = selected.stream().mapToLong(file ->
        file.getByteSize() == null ? 0L : file.getByteSize()).sum();
    if (totalBytes > properties.getMaxBytes()) {
      throw new BusinessException(ErrorCode.GITHUB_SYNC_LIMIT_EXCEEDED,
          "选择文件总大小超过上限，请排除不相关模块");
    }
  }

  private String fingerprint(String commitSha, List<GithubRepositoryFileEntity> files) {
    StringBuilder canonical = new StringBuilder(commitSha.toLowerCase(Locale.ROOT));
    files.stream().sorted(Comparator.comparing(GithubRepositoryFileEntity::getPath))
        .forEach(file -> canonical.append('\n')
            .append(file.getPath()).append(':').append(file.getBlobSha()));
    return GithubHashing.sha256(canonical.toString());
  }

  private GithubCodeEvidenceEntity toEvidence(
      GithubRepositoryEntity repository,
      GithubRepositoryFileEntity file,
      CodeChunk chunk,
      String parentSummary,
      LocalDateTime now
  ) {
    String contentHash = GithubHashing.sha256(chunk.content());
    String evidenceKey = repository.getUserId() + "|" + repository.getId() + "|"
        + repository.getFixedCommitSha() + "|" + file.getPath() + "|"
        + chunk.startLine() + "|" + chunk.endLine() + "|" + contentHash;
    String evidenceId = "gh-" + GithubHashing.sha256(evidenceKey).substring(0, 40);
    String sourceLocator = GithubSourceLocator.blob(
        repository.getRepositoryUrl(),
        repository.getFixedCommitSha(),
        file.getPath(),
        chunk.startLine(),
        chunk.endLine());
    return GithubCodeEvidenceEntity.builder()
        .ownerUserId(repository.getUserId())
        .dataDomain(DataDomain.GITHUB)
        .resourceId(GithubEvidenceIndexer.resourceId(repository.getId()))
        .resourceVersion(repository.getFixedCommitSha())
        .repositoryId(repository.getId())
        .commitSha(repository.getFixedCommitSha())
        .path(file.getPath())
        .language(file.getLanguage())
        .symbolName(truncate(chunk.symbolName(), 255))
        .symbolKind(chunk.symbolKind())
        .startLine(chunk.startLine())
        .endLine(chunk.endLine())
        .parentSummary(truncate(parentSummary, 1000))
        .content(chunk.content())
        .contentHash(contentHash)
        .evidenceId(evidenceId)
        .sourceLocator(sourceLocator)
        .createdAt(now)
        .build();
  }

  private String safeSyncError(
      BusinessException exception,
      GithubRepositorySyncStatus status
  ) {
    if (status == GithubRepositorySyncStatus.SOURCE_UNAVAILABLE) {
      return "固定 SHA 在 GitHub 已不可用，已保留最后一次成功快照";
    }
    if (exception.getCode().equals(ErrorCode.GITHUB_RATE_LIMITED.getCode())) {
      return "GitHub API 已限流，请稍后重试";
    }
    return "GitHub 固定快照同步失败，请稍后重试";
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }
}
