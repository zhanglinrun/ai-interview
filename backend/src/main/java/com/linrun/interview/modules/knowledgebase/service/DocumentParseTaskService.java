package com.linrun.interview.modules.knowledgebase.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.modules.knowledgebase.mapper.DocumentParseTaskMapper;
import com.linrun.interview.modules.knowledgebase.model.DocumentParseTaskEntity;
import com.linrun.interview.modules.knowledgebase.model.DocumentParseTaskStatus;
import com.linrun.interview.modules.knowledgebase.service.parse.DocumentParseRequest;
import com.linrun.interview.modules.knowledgebase.service.parse.mineru.MineruFailureCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** MinerU provider task 的事实记录；所有方法都使用显式 userId。 */
@Service
@RequiredArgsConstructor
public class DocumentParseTaskService {

  private static final int FAILURE_DETAIL_MAX_CHARS = 500;

  private final DocumentParseTaskMapper mapper;

  public DocumentParseTaskEntity create(DocumentParseRequest request) {
    LocalDateTime now = LocalDateTime.now();
    DocumentParseTaskEntity task = new DocumentParseTaskEntity();
    task.setUserId(request.userId());
    task.setDocumentId(request.documentId());
    task.setVersionId(request.versionId());
    task.setProvider("MINERU");
    task.setStatus(DocumentParseTaskStatus.CREATED);
    task.setAttempt(0);
    task.setFallbackUsed(false);
    task.setStorageKey(request.storageKey());
    task.setFileName(request.fileName());
    task.setContentType(request.contentType());
    task.setStartedAt(now);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return MapperUtils.save(mapper, task);
  }

  public void markSubmitted(
      DocumentParseTaskEntity task,
      String providerTaskId,
      LocalDateTime nextPollAt
  ) {
    if (task == null || task.getStatus().isTerminal()) {
      return;
    }
    task.setProviderTaskId(providerTaskId);
    task.setStatus(DocumentParseTaskStatus.SUBMITTED);
    task.setAttempt(task.getAttempt() == null ? 1 : task.getAttempt() + 1);
    task.setNextPollAt(nextPollAt);
    update(task);
  }

  public void markPolling(DocumentParseTaskEntity task, LocalDateTime nextPollAt) {
    if (task == null || task.getStatus().isTerminal()) {
      return;
    }
    task.setStatus(DocumentParseTaskStatus.POLLING);
    task.setNextPollAt(nextPollAt);
    update(task);
  }

  public void markSucceeded(DocumentParseTaskEntity task) {
    if (task == null || task.getStatus().isTerminal()) {
      return;
    }
    task.setStatus(DocumentParseTaskStatus.SUCCEEDED);
    task.setNextPollAt(null);
    task.setCompletedAt(LocalDateTime.now());
    update(task);
  }

  public void markFailed(
      DocumentParseTaskEntity task,
      MineruFailureCode code,
      String detail
  ) {
    if (task == null || task.getStatus().isTerminal()) {
      return;
    }
    task.setStatus(DocumentParseTaskStatus.FAILED);
    task.setFailureCode(code.name());
    task.setFailureDetail(truncate(detail));
    task.setNextPollAt(null);
    update(task);
  }

  public void markFallbackSucceeded(
      DocumentParseTaskEntity task,
      MineruFailureCode reason
  ) {
    if (task == null || task.getStatus() == DocumentParseTaskStatus.SUCCEEDED) {
      return;
    }
    task.setStatus(DocumentParseTaskStatus.FALLBACK_SUCCEEDED);
    task.setFallbackUsed(true);
    task.setFallbackReason(reason.name());
    task.setNextPollAt(null);
    task.setCompletedAt(LocalDateTime.now());
    update(task);
  }

  public void markFallbackFailed(
      DocumentParseTaskEntity task,
      MineruFailureCode reason,
      String detail
  ) {
    if (task == null || task.getStatus() == DocumentParseTaskStatus.SUCCEEDED) {
      return;
    }
    task.setStatus(DocumentParseTaskStatus.FALLBACK_FAILED);
    task.setFallbackUsed(true);
    task.setFallbackReason(reason.name());
    task.setFailureDetail(truncate(detail));
    task.setNextPollAt(null);
    task.setCompletedAt(LocalDateTime.now());
    update(task);
  }

  public List<DocumentParseTaskEntity> listRecoverable(LocalDateTime staleBefore, int limit) {
    return mapper.selectList(Wrappers.<DocumentParseTaskEntity>lambdaQuery()
        .in(DocumentParseTaskEntity::getStatus,
            DocumentParseTaskStatus.SUBMITTED, DocumentParseTaskStatus.POLLING)
        .le(DocumentParseTaskEntity::getUpdatedAt, staleBefore)
        .orderByAsc(DocumentParseTaskEntity::getNextPollAt)
        .last("LIMIT " + Math.max(limit, 1)));
  }

  public Optional<DocumentParseTaskEntity> findLatest(Long userId, Long documentId, Long versionId) {
    return mapper.selectList(Wrappers.<DocumentParseTaskEntity>lambdaQuery()
            .eq(DocumentParseTaskEntity::getUserId, userId)
            .eq(DocumentParseTaskEntity::getDocumentId, documentId)
            .eq(DocumentParseTaskEntity::getVersionId, versionId)
            .orderByDesc(DocumentParseTaskEntity::getCreatedAt)
            .last("LIMIT 1"))
        .stream()
        .findFirst();
  }

  public int deleteByDocument(Long userId, Long documentId) {
    return mapper.delete(Wrappers.<DocumentParseTaskEntity>lambdaQuery()
        .eq(DocumentParseTaskEntity::getUserId, userId)
        .eq(DocumentParseTaskEntity::getDocumentId, documentId));
  }

  private void update(DocumentParseTaskEntity task) {
    task.setUpdatedAt(LocalDateTime.now());
    MapperUtils.save(mapper, task);
  }

  private String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= FAILURE_DETAIL_MAX_CHARS
        ? value : value.substring(0, FAILURE_DETAIL_MAX_CHARS);
  }
}
