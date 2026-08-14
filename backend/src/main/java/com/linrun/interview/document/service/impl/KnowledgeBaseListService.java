package com.linrun.interview.document.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.rag.model.EvidenceScope;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.infra.persistence.EntityQueries;
import com.linrun.interview.infra.persistence.MapperUtils;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.document.service.FileStorageService;
import com.linrun.interview.infra.redis.RedisService;
import com.linrun.interview.document.converter.KnowledgeBaseMapper;
import com.linrun.interview.document.constant.DocumentAccessScope;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.vo.KnowledgeBaseListItemDTO;
import com.linrun.interview.document.vo.KnowledgeBaseStatsDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {

  private static final Duration NULL_ID_TTL = Duration.ofMinutes(2);
  private static final String NULL_ID_PREFIX = "kb:null:";

  private final KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final FileStorageService fileStorageService;
  private final RedisService redisService;

  public List<KnowledgeBaseListItemDTO> listKnowledgeBases(DocumentStatus docStatus, String sortBy) {
    Long userId = UserContext.requireUserId();
    var query = Wrappers.<KnowledgeBaseEntity>lambdaQuery()
      .and(w -> w.eq(KnowledgeBaseEntity::getUserId, userId)
        .or()
        .eq(KnowledgeBaseEntity::getAccessibleBy, DocumentAccessScope.PUBLIC.name()))
      .orderByDesc(KnowledgeBaseEntity::getUploadedAt);
    if (docStatus != null) {
      query.eq(KnowledgeBaseEntity::getDocStatus, docStatus);
    }
    List<KnowledgeBaseEntity> entities = knowledgeBaseEntityMapper.selectList(query);

    if (sortBy != null && !sortBy.isBlank() && !sortBy.equalsIgnoreCase("time")) {
      entities = sortEntities(entities, sortBy);
    }
    return toListItems(entities, userId);
  }

  public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
    return listKnowledgeBases(null, null);
  }

  public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
    return findReadableEntity(id).map(e -> toListItem(e, UserContext.requireUserId()));
  }

  public Optional<KnowledgeBaseEntity> getKnowledgeBaseEntity(Long id) {
    return findReadableEntity(id);
  }

  public List<String> getKnowledgeBaseNames(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    Map<Long, String> nameMap = getKnowledgeBaseNameMap(ids);
    return ids.stream()
      .map(id -> nameMap.getOrDefault(id, "未知知识库"))
      .toList();
  }

  public Map<Long, String> getKnowledgeBaseNameMap(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return Map.of();
    }
    List<Long> uniqueIds = ids.stream().distinct().toList();
    return listReadableByIds(UserContext.requireUserId(), uniqueIds).stream()
      .collect(Collectors.toMap(KnowledgeBaseEntity::getId, KnowledgeBaseEntity::getName));
  }

  public List<String> getAllCategories() {
    return knowledgeBaseEntityMapper.selectCategoriesByUserId(UserContext.requireUserId());
  }

  public List<KnowledgeBaseListItemDTO> listByCategory(String category) {
    Long userId = UserContext.requireUserId();
    var query = Wrappers.<KnowledgeBaseEntity>lambdaQuery()
      .and(w -> w.eq(KnowledgeBaseEntity::getUserId, userId)
        .or()
        .eq(KnowledgeBaseEntity::getAccessibleBy, DocumentAccessScope.PUBLIC.name()))
      .orderByDesc(KnowledgeBaseEntity::getUploadedAt);
    if (category == null || category.isBlank()) {
      query.isNull(KnowledgeBaseEntity::getCategory);
    } else {
      query.eq(KnowledgeBaseEntity::getCategory, category);
    }
    return toListItems(knowledgeBaseEntityMapper.selectList(query), userId);
  }

  @Transactional
  public void updateCategory(Long id, String category) {
    KnowledgeBaseEntity entity = findEntityByUserAndId(id)
      .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
    entity.setCategory(category != null && !category.isBlank() ? category : null);
    MapperUtils.save(knowledgeBaseEntityMapper, entity);
    log.info("更新知识库分类: id={}, category={}", id, category);
  }

  public List<KnowledgeBaseListItemDTO> search(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return listKnowledgeBases();
    }
    String kw = keyword.trim();
    Long userId = UserContext.requireUserId();
    List<KnowledgeBaseEntity> entities = knowledgeBaseEntityMapper.selectList(
      Wrappers.<KnowledgeBaseEntity>lambdaQuery()
        .and(w -> w.eq(KnowledgeBaseEntity::getUserId, userId)
          .or()
          .eq(KnowledgeBaseEntity::getAccessibleBy, DocumentAccessScope.PUBLIC.name()))
        .and(w -> w.like(KnowledgeBaseEntity::getName, kw)
          .or().like(KnowledgeBaseEntity::getOriginalFilename, kw)
          .or().like(KnowledgeBaseEntity::getDescription, kw))
        .orderByDesc(KnowledgeBaseEntity::getUploadedAt));
    return toListItems(entities, userId);
  }

  public List<KnowledgeBaseListItemDTO> listSorted(String sortBy) {
    return listKnowledgeBases(null, sortBy);
  }

  private List<KnowledgeBaseEntity> sortEntities(List<KnowledgeBaseEntity> entities, String sortBy) {
    return switch (sortBy.toLowerCase()) {
      case "size" -> entities.stream()
        .sorted((a, b) -> Long.compare(
          b.getFileSize() != null ? b.getFileSize() : 0L,
          a.getFileSize() != null ? a.getFileSize() : 0L))
        .toList();
      case "access" -> entities.stream()
        .sorted((a, b) -> Integer.compare(
          b.getAccessCount() != null ? b.getAccessCount() : 0,
          a.getAccessCount() != null ? a.getAccessCount() : 0))
        .toList();
      case "question" -> entities.stream()
        .sorted((a, b) -> Integer.compare(
          b.getQuestionCount() != null ? b.getQuestionCount() : 0,
          a.getQuestionCount() != null ? a.getQuestionCount() : 0))
        .toList();
      default -> entities;
    };
  }

  public KnowledgeBaseStatsDTO getStatistics() {
    Long userId = UserContext.requireUserId();
    List<KnowledgeBaseEntity> all = EntityQueries.listByUserId(
      knowledgeBaseEntityMapper, userId, KnowledgeBaseEntity::getUserId);
    long totalDocs = all.size();
    long totalAccess = all.stream()
      .mapToLong(kb -> kb.getAccessCount() != null ? kb.getAccessCount() : 0)
      .sum();
    long vectorStored = all.stream()
      .filter(kb -> DocumentStatus.VECTOR_STORED == kb.getDocStatus())
      .count();
    long processing = all.stream()
      .filter(kb -> kb.getDocStatus() == DocumentStatus.CONVERTED
        || kb.getDocStatus() == DocumentStatus.CHUNKED)
      .count();
    long totalQuestions = all.stream()
      .mapToLong(kb -> kb.getQuestionCount() != null ? kb.getQuestionCount() : 0)
      .sum();
    return new KnowledgeBaseStatsDTO(
      totalDocs, totalQuestions, totalAccess, vectorStored, processing);
  }

  public byte[] downloadFile(Long id) {
    KnowledgeBaseEntity entity = findReadableEntity(id)
      .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
    String storageKey = entity.getStorageKey();
    if (storageKey == null || storageKey.isBlank()) {
      throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "存储文件路径不存在");
    }
    log.info("下载知识库文件: id={}, filename={}", id, entity.getOriginalFilename());
    return fileStorageService.downloadFile(storageKey);
  }

  public KnowledgeBaseEntity getEntityForDownload(Long id) {
    return findReadableEntity(id)
      .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
  }

  /** 可读：本人文档或他人公开文档 */
  public Optional<KnowledgeBaseEntity> findReadableEntity(Long id) {
    if (id == null) {
      return Optional.empty();
    }
    Long userId = UserContext.requireUserId();
    String nullKey = NULL_ID_PREFIX + userId + ":" + id;
    if (Boolean.TRUE.equals(redisService.get(nullKey))) {
      return Optional.empty();
    }
    KnowledgeBaseEntity entity = knowledgeBaseEntityMapper.selectById(id);
    if (entity == null) {
      redisService.set(nullKey, true, NULL_ID_TTL);
      return Optional.empty();
    }
    if (userId.equals(entity.getUserId())) {
      return Optional.of(entity);
    }
    if (DocumentAccessScope.PUBLIC.name().equalsIgnoreCase(entity.getAccessibleBy())) {
      return Optional.of(entity);
    }
    // 他人私有文档对当前用户不可读，缓存空值防穿透（含私有→公开的 2 分钟陈旧窗口，可接受）
    redisService.set(nullKey, true, NULL_ID_TTL);
    return Optional.empty();
  }

  public List<KnowledgeBaseEntity> listReadableByIds(Long userId, List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    if (userId == null) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "知识库访问缺少用户身份");
    }
    List<Long> uniqueIds = ids.stream()
      .filter(java.util.Objects::nonNull)
      .distinct()
      .toList();
    if (uniqueIds.isEmpty()) {
      return List.of();
    }
    List<KnowledgeBaseEntity> candidates = knowledgeBaseEntityMapper.selectList(
      Wrappers.<KnowledgeBaseEntity>lambdaQuery()
        .in(KnowledgeBaseEntity::getId, uniqueIds)
        .and(wrapper -> wrapper.eq(KnowledgeBaseEntity::getUserId, userId)
          .or()
          .eq(KnowledgeBaseEntity::getAccessibleBy, DocumentAccessScope.PUBLIC.name())));
    Map<Long, KnowledgeBaseEntity> readableById = candidates.stream()
      // 查询条件之外再校验一次，避免错误 Mapper 或未来 SQL 改动放宽私有库边界。
      .filter(entity -> userId.equals(entity.getUserId())
        || DocumentAccessScope.PUBLIC.name().equalsIgnoreCase(entity.getAccessibleBy()))
      .collect(Collectors.toMap(
        KnowledgeBaseEntity::getId,
        entity -> entity,
        (left, right) -> left,
        LinkedHashMap::new));
    return uniqueIds.stream()
      .map(readableById::get)
      .filter(java.util.Objects::nonNull)
      .toList();
  }

  /**
   * 将访问者可读的候选人知识库按真实 owner 分组为 ES 证据范围。
   *
   * <p>PUBLIC 只授予读取权限，不改变资源所有者；因此不能用访问者 ID 代替 owner 过滤。
   * 私有越权或不存在的 ID 会在进入 ES 前直接拒绝，绝不退化为无 owner 条件的检索。
   */
  public List<EvidenceScope> resolveReadableCandidateScopes(Long userId, List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    List<Long> uniqueIds = ids.stream()
      .filter(java.util.Objects::nonNull)
      .distinct()
      .toList();
    List<KnowledgeBaseEntity> readable = listReadableByIds(userId, uniqueIds);
    Map<Long, KnowledgeBaseEntity> readableById = readable.stream()
      .collect(Collectors.toMap(KnowledgeBaseEntity::getId, entity -> entity));
    List<Long> rejectedIds = uniqueIds.stream()
      .filter(id -> !readableById.containsKey(id))
      .toList();
    if (!rejectedIds.isEmpty()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
        "知识库不存在或无权访问: " + rejectedIds);
    }

    Map<Long, List<Long>> idsByOwner = new LinkedHashMap<>();
    for (Long id : uniqueIds) {
      Long ownerUserId = readableById.get(id).getUserId();
      if (ownerUserId == null || ownerUserId <= 0) {
        throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
          "知识库缺少有效所有者: " + id);
      }
      idsByOwner.computeIfAbsent(ownerUserId, ignored -> new ArrayList<>()).add(id);
    }
    return idsByOwner.entrySet().stream()
      .map(entry -> EvidenceScope.candidateKnowledgeBases(entry.getKey(), entry.getValue()))
      .toList();
  }

  private List<KnowledgeBaseListItemDTO> toListItems(List<KnowledgeBaseEntity> entities, Long userId) {
    return entities.stream().map(e -> toListItem(e, userId)).toList();
  }

  private KnowledgeBaseListItemDTO toListItem(KnowledgeBaseEntity entity, Long userId) {
    KnowledgeBaseListItemDTO base = knowledgeBaseMapper.toListItemDTO(entity);
    boolean owned = userId.equals(entity.getUserId());
    return new KnowledgeBaseListItemDTO(
      base.id(), base.name(), base.category(), base.originalFilename(),
      base.fileSize(), base.contentType(), base.uploadedAt(), base.lastAccessedAt(),
      base.accessCount(), base.questionCount(), base.docStatus(), base.currentVersionId(),
      base.accessibleBy(), base.expireDate(), owned);
  }

  private Optional<KnowledgeBaseEntity> findEntityByUserAndId(Long id) {
    if (id == null) {
      return Optional.empty();
    }
    Long userId = UserContext.requireUserId();
    String nullKey = NULL_ID_PREFIX + userId + ":" + id;
    if (Boolean.TRUE.equals(redisService.get(nullKey))) {
      return Optional.empty();
    }
    Optional<KnowledgeBaseEntity> result = EntityQueries.byUserAndId(
      knowledgeBaseEntityMapper, userId, id, KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId);
    if (result.isEmpty()) {
      redisService.set(nullKey, true, NULL_ID_TTL);
    }
    return result;
  }
}
