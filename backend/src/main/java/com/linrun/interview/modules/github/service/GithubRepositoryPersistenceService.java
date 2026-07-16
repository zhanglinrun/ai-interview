package com.linrun.interview.modules.github.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.github.dto.BindGithubRepositoryRequest.ContributionDeclaration;
import com.linrun.interview.modules.github.mapper.GithubCodeEvidenceMapper;
import com.linrun.interview.modules.github.mapper.GithubRepositoryFileMapper;
import com.linrun.interview.modules.github.mapper.GithubRepositoryMapper;
import com.linrun.interview.modules.github.model.GithubCodeEvidenceEntity;
import com.linrun.interview.modules.github.model.GithubRepositoryEntity;
import com.linrun.interview.modules.github.model.GithubRepositoryFileEntity;
import com.linrun.interview.modules.github.model.GithubRepositorySyncStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GitHub 证据的短事务边界。所有 GitHub HTTP、Embedding 和 MCP 调用都必须在本类事务之外完成。
 */
@Service
@RequiredArgsConstructor
public class GithubRepositoryPersistenceService {

  private static final int INSERT_BATCH_SIZE = 100;

  private final GithubRepositoryMapper repositoryMapper;
  private final GithubRepositoryFileMapper fileMapper;
  private final GithubCodeEvidenceMapper evidenceMapper;
  private final ObjectMapper objectMapper;

  public GithubRepositoryEntity requireRepository(Long userId, Long repositoryId) {
    GithubRepositoryEntity repository = repositoryMapper.selectOne(
        Wrappers.<GithubRepositoryEntity>lambdaQuery()
            .eq(GithubRepositoryEntity::getId, repositoryId)
            .eq(GithubRepositoryEntity::getUserId, userId));
    if (repository == null) {
      throw new BusinessException(ErrorCode.GITHUB_REPOSITORY_NOT_FOUND,
          "GitHub 仓库绑定不存在或无权访问");
    }
    return repository;
  }

  public List<GithubRepositoryEntity> listRepositories(Long userId) {
    return repositoryMapper.selectList(Wrappers.<GithubRepositoryEntity>lambdaQuery()
        .eq(GithubRepositoryEntity::getUserId, userId)
        .orderByDesc(GithubRepositoryEntity::getUpdatedAt));
  }

  public List<GithubRepositoryFileEntity> listFiles(Long userId, Long repositoryId) {
    return fileMapper.selectList(Wrappers.<GithubRepositoryFileEntity>lambdaQuery()
        .eq(GithubRepositoryFileEntity::getUserId, userId)
        .eq(GithubRepositoryFileEntity::getRepositoryId, repositoryId)
        .orderByAsc(GithubRepositoryFileEntity::getPath));
  }

  public GithubRepositoryFileEntity findFile(
      Long userId,
      Long repositoryId,
      String commitSha,
      String path
  ) {
    return fileMapper.selectOne(Wrappers.<GithubRepositoryFileEntity>lambdaQuery()
        .eq(GithubRepositoryFileEntity::getUserId, userId)
        .eq(GithubRepositoryFileEntity::getRepositoryId, repositoryId)
        .eq(GithubRepositoryFileEntity::getCommitSha, commitSha)
        .eq(GithubRepositoryFileEntity::getPath, path));
  }

  public List<GithubCodeEvidenceEntity> listEvidence(
      Long userId,
      Long repositoryId,
      String commitSha,
      int limit
  ) {
    return evidenceMapper.selectSnapshot(userId, repositoryId, commitSha, limit);
  }

  /**
   * 返回已完整建立向量索引的证据数量；快照为空或存在任一未回写 embeddingId 的证据时返回 0。
   *
   * <p>同步过程先提交固定 SHA 快照，再调用外部 Embedding/ES。若进程在两步之间退出，仓库记录
   * 可能仍是 SYNCED，但该快照不能作为幂等命中复用。</p>
   */
  public int countCompletelyIndexedEvidence(
      Long userId,
      Long repositoryId,
      String commitSha
  ) {
    long total = evidenceMapper.selectCount(
        Wrappers.<GithubCodeEvidenceEntity>lambdaQuery()
            .eq(GithubCodeEvidenceEntity::getOwnerUserId, userId)
            .eq(GithubCodeEvidenceEntity::getRepositoryId, repositoryId)
            .eq(GithubCodeEvidenceEntity::getCommitSha, commitSha));
    if (total == 0) {
      return 0;
    }
    long indexed = evidenceMapper.selectCount(
        Wrappers.<GithubCodeEvidenceEntity>lambdaQuery()
            .eq(GithubCodeEvidenceEntity::getOwnerUserId, userId)
            .eq(GithubCodeEvidenceEntity::getRepositoryId, repositoryId)
            .eq(GithubCodeEvidenceEntity::getCommitSha, commitSha)
            .isNotNull(GithubCodeEvidenceEntity::getEmbeddingId)
            .ne(GithubCodeEvidenceEntity::getEmbeddingId, ""));
    return total == indexed ? Math.toIntExact(total) : 0;
  }

  public GithubCodeEvidenceEntity findEvidence(
      Long userId,
      Long repositoryId,
      String commitSha,
      String evidenceId
  ) {
    return evidenceMapper.selectOne(Wrappers.<GithubCodeEvidenceEntity>lambdaQuery()
        .eq(GithubCodeEvidenceEntity::getOwnerUserId, userId)
        .eq(GithubCodeEvidenceEntity::getRepositoryId, repositoryId)
        .eq(GithubCodeEvidenceEntity::getCommitSha, commitSha)
        .eq(GithubCodeEvidenceEntity::getEvidenceId, evidenceId));
  }

  @Transactional(rollbackFor = Exception.class)
  public GithubRepositoryEntity createOrRefreshBinding(
      GithubRepositoryEntity candidate,
      ContributionDeclaration contribution,
      List<GithubRepositoryFileEntity> manifest
  ) {
    GithubRepositoryEntity existing = repositoryMapper.selectOne(
        Wrappers.<GithubRepositoryEntity>lambdaQuery()
            .eq(GithubRepositoryEntity::getUserId, candidate.getUserId())
            .eq(GithubRepositoryEntity::getOwnerName, candidate.getOwnerName())
            .eq(GithubRepositoryEntity::getRepositoryName, candidate.getRepositoryName())
            .eq(GithubRepositoryEntity::getFixedCommitSha, candidate.getFixedCommitSha()));
    LocalDateTime now = LocalDateTime.now();
    if (existing != null) {
      applyContribution(existing, contribution);
      existing.setUpdatedAt(now);
      existing.setSourceAvailable(true);
      repositoryMapper.updateById(existing);
      boolean hasSnapshot = evidenceMapper.selectCount(
          Wrappers.<GithubCodeEvidenceEntity>lambdaQuery()
              .eq(GithubCodeEvidenceEntity::getOwnerUserId, existing.getUserId())
              .eq(GithubCodeEvidenceEntity::getRepositoryId, existing.getId())) > 0;
      if (!hasSnapshot && existing.getSyncStatus() != GithubRepositorySyncStatus.SYNCED
          && existing.getSyncStatus() != GithubRepositorySyncStatus.PARTIAL) {
        replaceManifest(existing, manifest, now);
      }
      return existing;
    }

    candidate.setCoreModulesJson(toJson(contribution.coreModules()));
    candidate.setResponsibilities(contribution.responsibilities().strip());
    candidate.setKeyDecisions(contribution.keyDecisions().strip());
    candidate.setProblemsSolved(contribution.problemsSolved().strip());
    candidate.setSyncStatus(GithubRepositorySyncStatus.AWAITING_SELECTION);
    candidate.setSyncedFileCount(0);
    candidate.setSyncedBytes(0L);
    candidate.setSourceAvailable(true);
    candidate.setCreatedAt(now);
    candidate.setUpdatedAt(now);
    repositoryMapper.insert(candidate);
    manifest.forEach(file -> {
      file.setRepositoryId(candidate.getId());
      file.setUserId(candidate.getUserId());
      file.setCreatedAt(now);
      file.setUpdatedAt(now);
    });
    insertFiles(manifest);
    return candidate;
  }

  public void claimSync(Long userId, Long repositoryId) {
    if (repositoryMapper.claimSync(userId, repositoryId) == 0) {
      GithubRepositoryEntity current = requireRepository(userId, repositoryId);
      if (current.getSyncStatus() == GithubRepositorySyncStatus.SYNCING) {
        throw new BusinessException(ErrorCode.GITHUB_REPOSITORY_NOT_READY,
            "该仓库正在同步，请勿重复提交");
      }
      throw new BusinessException(ErrorCode.GITHUB_REPOSITORY_NOT_READY, "仓库当前状态不能同步");
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void replaceSnapshot(
      GithubRepositoryEntity repository,
      List<GithubRepositoryFileEntity> manifest,
      List<GithubCodeEvidenceEntity> chunks,
      String fingerprint,
      int syncedFiles,
      long syncedBytes,
      int blockedFiles
  ) {
    Long userId = repository.getUserId();
    Long repositoryId = repository.getId();
    evidenceMapper.delete(Wrappers.<GithubCodeEvidenceEntity>lambdaQuery()
        .eq(GithubCodeEvidenceEntity::getOwnerUserId, userId)
        .eq(GithubCodeEvidenceEntity::getRepositoryId, repositoryId));
    fileMapper.delete(Wrappers.<GithubRepositoryFileEntity>lambdaQuery()
        .eq(GithubRepositoryFileEntity::getUserId, userId)
        .eq(GithubRepositoryFileEntity::getRepositoryId, repositoryId));
    insertFiles(manifest);
    insertEvidence(chunks);

    LocalDateTime now = LocalDateTime.now();
    repository.setSyncStatus(blockedFiles > 0
        ? GithubRepositorySyncStatus.PARTIAL : GithubRepositorySyncStatus.SYNCED);
    repository.setSyncFingerprint(fingerprint);
    repository.setSyncedFileCount(syncedFiles);
    repository.setSyncedBytes(syncedBytes);
    repository.setSyncError(blockedFiles > 0 ? blockedFiles + " 个文件因安全策略未同步" : null);
    repository.setSourceAvailable(true);
    repository.setLastSyncedAt(now);
    repository.setUpdatedAt(now);
    repositoryMapper.update(Wrappers.<GithubRepositoryEntity>lambdaUpdate()
        .eq(GithubRepositoryEntity::getId, repositoryId)
        .eq(GithubRepositoryEntity::getUserId, userId)
        .set(GithubRepositoryEntity::getSyncStatus, repository.getSyncStatus())
        .set(GithubRepositoryEntity::getSyncFingerprint, fingerprint)
        .set(GithubRepositoryEntity::getSyncedFileCount, syncedFiles)
        .set(GithubRepositoryEntity::getSyncedBytes, syncedBytes)
        .set(GithubRepositoryEntity::getSyncError, repository.getSyncError())
        .set(GithubRepositoryEntity::getSourceAvailable, true)
        .set(GithubRepositoryEntity::getLastSyncedAt, now)
        .set(GithubRepositoryEntity::getUpdatedAt, now));
  }

  public void markSyncFailure(
      Long userId,
      Long repositoryId,
      GithubRepositorySyncStatus status,
      String safeError
  ) {
    repositoryMapper.update(Wrappers.<GithubRepositoryEntity>lambdaUpdate()
        .eq(GithubRepositoryEntity::getId, repositoryId)
        .eq(GithubRepositoryEntity::getUserId, userId)
        .set(GithubRepositoryEntity::getSyncStatus, status)
        .set(GithubRepositoryEntity::getSyncError, truncate(safeError, 500))
        .set(GithubRepositoryEntity::getSourceAvailable,
            status != GithubRepositorySyncStatus.SOURCE_UNAVAILABLE)
        .set(GithubRepositoryEntity::getUpdatedAt, LocalDateTime.now()));
  }

  public void updateEmbeddingIds(List<GithubCodeEvidenceEntity> chunks, List<String> ids) {
    if (chunks.size() != ids.size()) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "GitHub 证据向量数量不一致");
    }
    for (int index = 0; index < chunks.size(); index++) {
      chunks.get(index).setEmbeddingId(ids.get(index));
    }
    if (!chunks.isEmpty()) {
      evidenceMapper.batchUpdateEmbeddingIds(chunks.getFirst().getOwnerUserId(), chunks);
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteRepository(Long userId, Long repositoryId) {
    requireRepository(userId, repositoryId);
    evidenceMapper.delete(Wrappers.<GithubCodeEvidenceEntity>lambdaQuery()
        .eq(GithubCodeEvidenceEntity::getOwnerUserId, userId)
        .eq(GithubCodeEvidenceEntity::getRepositoryId, repositoryId));
    fileMapper.delete(Wrappers.<GithubRepositoryFileEntity>lambdaQuery()
        .eq(GithubRepositoryFileEntity::getUserId, userId)
        .eq(GithubRepositoryFileEntity::getRepositoryId, repositoryId));
    repositoryMapper.delete(Wrappers.<GithubRepositoryEntity>lambdaQuery()
        .eq(GithubRepositoryEntity::getUserId, userId)
        .eq(GithubRepositoryEntity::getId, repositoryId));
  }

  public List<String> parseCoreModules(GithubRepositoryEntity repository) {
    if (repository.getCoreModulesJson() == null || repository.getCoreModulesJson().isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(repository.getCoreModulesJson(), new TypeReference<>() {
      });
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "GitHub 核心模块声明解析失败", e);
    }
  }

  private void replaceManifest(
      GithubRepositoryEntity repository,
      List<GithubRepositoryFileEntity> manifest,
      LocalDateTime now
  ) {
    fileMapper.delete(Wrappers.<GithubRepositoryFileEntity>lambdaQuery()
        .eq(GithubRepositoryFileEntity::getUserId, repository.getUserId())
        .eq(GithubRepositoryFileEntity::getRepositoryId, repository.getId()));
    manifest.forEach(file -> {
      file.setRepositoryId(repository.getId());
      file.setUserId(repository.getUserId());
      file.setCreatedAt(now);
      file.setUpdatedAt(now);
    });
    insertFiles(manifest);
  }

  private void applyContribution(
      GithubRepositoryEntity repository,
      ContributionDeclaration contribution
  ) {
    repository.setCoreModulesJson(toJson(contribution.coreModules()));
    repository.setResponsibilities(contribution.responsibilities().strip());
    repository.setKeyDecisions(contribution.keyDecisions().strip());
    repository.setProblemsSolved(contribution.problemsSolved().strip());
  }

  private void insertFiles(List<GithubRepositoryFileEntity> files) {
    for (int from = 0; from < files.size(); from += INSERT_BATCH_SIZE) {
      fileMapper.batchInsert(files.subList(from, Math.min(files.size(), from + INSERT_BATCH_SIZE)));
    }
  }

  private void insertEvidence(List<GithubCodeEvidenceEntity> chunks) {
    for (int from = 0; from < chunks.size(); from += INSERT_BATCH_SIZE) {
      evidenceMapper.batchInsert(chunks.subList(from, Math.min(chunks.size(), from + INSERT_BATCH_SIZE)));
    }
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "GitHub 贡献声明序列化失败", e);
    }
  }

  private String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
