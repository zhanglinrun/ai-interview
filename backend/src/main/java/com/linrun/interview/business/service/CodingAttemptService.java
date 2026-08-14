package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.document.service.impl.FileHashService;
import com.linrun.interview.business.vo.CodingAttemptDTO;
import com.linrun.interview.business.vo.CodingDraftDTO;
import com.linrun.interview.business.vo.CreateCodingAttemptRequest;
import com.linrun.interview.business.vo.SaveCodingDraftRequest;
import com.linrun.interview.business.mapper.CodingAttemptMapper;
import com.linrun.interview.business.mapper.CodingDraftMapper;
import com.linrun.interview.business.entity.CodingAttemptEntity;
import com.linrun.interview.business.constant.CodingAttemptStatus;
import com.linrun.interview.business.entity.CodingDraftEntity;
import com.linrun.interview.business.entity.CodingProblemVersionEntity;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CodingAttemptService {

  private final AlgorithmCatalogService catalogService;
  private final TestHarnessFactory harnessFactory;
  private final CodingAttemptMapper attemptMapper;
  private final CodingDraftMapper draftMapper;
  private final FileHashService fileHashService;

  @Transactional(rollbackFor = Exception.class)
  public CodingAttemptDTO create(Long userId, CreateCodingAttemptRequest request) {
    requireUserId(userId);
    CodingProblemVersionEntity version = catalogService.requireEnabledVersion(
        request.problemVersionId(), request.language());
    String template = harnessFactory.languageSpec(version, request.language()).template();
    LocalDateTime now = LocalDateTime.now();
    CodingAttemptEntity attempt = CodingAttemptEntity.builder()
        .attemptId(UUID.randomUUID().toString())
        .userId(userId)
        .problemVersionId(version.getId())
        .mode(request.mode())
        .contextId(trimToNull(request.contextId()))
        .language(request.language())
        .status(CodingAttemptStatus.IN_PROGRESS)
        .startedAt(now)
        .createdAt(now)
        .updatedAt(now)
        .lockVersion(0)
        .build();
    attemptMapper.insert(attempt);
    draftMapper.insert(CodingDraftEntity.builder()
        .userId(userId)
        .attemptId(attempt.getId())
        .language(request.language())
        .sourceCode(template)
        .codeHash(hash(template))
        .revision(0)
        .createdAt(now)
        .updatedAt(now)
        .build());
    return toDTO(attempt);
  }

  public CodingAttemptDTO get(Long userId, String attemptId) {
    return toDTO(requireOwned(userId, attemptId));
  }

  public CodingDraftDTO getDraft(Long userId, String attemptId) {
    CodingAttemptEntity attempt = requireOwned(userId, attemptId);
    return toDraftDTO(attempt, requireDraft(userId, attempt.getId()));
  }

  @Transactional(rollbackFor = Exception.class)
  public CodingDraftDTO saveDraft(
      Long userId,
      String attemptId,
      SaveCodingDraftRequest request
  ) {
    CodingAttemptEntity attempt = requireOwned(userId, attemptId);
    if (attempt.getStatus() == CodingAttemptStatus.ABORTED
        || attempt.getStatus() == CodingAttemptStatus.COMPLETED) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "当前算法作答已结束，不能修改草稿");
    }
    int updated = draftMapper.updateOwnedDraft(
        attempt.getId(), userId, request.expectedRevision(), request.sourceCode(),
        hash(request.sourceCode()), LocalDateTime.now());
    if (updated == 0) {
      throw new BusinessException(ErrorCode.CODING_DRAFT_CONFLICT);
    }
    return toDraftDTO(attempt, requireDraft(userId, attempt.getId()));
  }

  public CodingAttemptEntity requireOwned(Long userId, String attemptId) {
    requireUserId(userId);
    if (attemptId == null || attemptId.isBlank()) {
      throw new BusinessException(ErrorCode.CODING_ATTEMPT_NOT_FOUND);
    }
    CodingAttemptEntity entity = attemptMapper.selectOne(
        Wrappers.<CodingAttemptEntity>lambdaQuery()
            .eq(CodingAttemptEntity::getUserId, userId)
            .eq(CodingAttemptEntity::getAttemptId, attemptId));
    if (entity == null) {
      throw new BusinessException(ErrorCode.CODING_ATTEMPT_NOT_FOUND);
    }
    return entity;
  }

  private CodingDraftEntity requireDraft(Long userId, Long attemptId) {
    CodingDraftEntity draft = draftMapper.selectOne(
        Wrappers.<CodingDraftEntity>lambdaQuery()
            .eq(CodingDraftEntity::getUserId, userId)
            .eq(CodingDraftEntity::getAttemptId, attemptId));
    if (draft == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "算法草稿不存在");
    }
    return draft;
  }

  private CodingAttemptDTO toDTO(CodingAttemptEntity entity) {
    return new CodingAttemptDTO(
        entity.getAttemptId(), entity.getProblemVersionId(), entity.getMode(),
        entity.getContextId(), entity.getLanguage(), entity.getStatus(), entity.getStartedAt(),
        entity.getSubmittedAt(), entity.getCompletedAt());
  }

  private CodingDraftDTO toDraftDTO(
      CodingAttemptEntity attempt,
      CodingDraftEntity draft
  ) {
    return new CodingDraftDTO(
        attempt.getAttemptId(), draft.getLanguage(), draft.getSourceCode(),
        draft.getRevision(), draft.getUpdatedAt());
  }

  private String hash(String sourceCode) {
    return fileHashService.calculateHash(sourceCode.getBytes(StandardCharsets.UTF_8));
  }

  private String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private void requireUserId(Long userId) {
    if (userId == null || userId <= 0) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或 token 无效");
    }
  }
}
