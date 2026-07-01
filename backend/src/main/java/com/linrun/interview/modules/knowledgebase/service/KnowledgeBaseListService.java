package com.linrun.interview.modules.knowledgebase.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.infrastructure.file.FileStorageService;
import com.linrun.interview.infrastructure.mapper.KnowledgeBaseMapper;
import com.linrun.interview.modules.knowledgebase.constant.DocumentStatus;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.modules.knowledgebase.mapper.RagChatMessageMapper;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import com.linrun.interview.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {

  private final KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;
  private final RagChatMessageMapper ragChatMessageMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final FileStorageService fileStorageService;

  public List<KnowledgeBaseListItemDTO> listKnowledgeBases(DocumentStatus docStatus, String sortBy) {
    Long userId = UserContext.requireUserId();
    List<KnowledgeBaseEntity> entities;
    if (docStatus != null) {
      entities = knowledgeBaseEntityMapper.selectList(
        Wrappers.<KnowledgeBaseEntity>lambdaQuery()
          .eq(KnowledgeBaseEntity::getUserId, userId)
          .eq(KnowledgeBaseEntity::getDocStatus, docStatus)
          .orderByDesc(KnowledgeBaseEntity::getUploadedAt));
    } else {
      entities = EntityQueries.listByUserIdOrderByDesc(
        knowledgeBaseEntityMapper, userId,
        KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getUploadedAt);
    }

    if (sortBy != null && !sortBy.isBlank() && !sortBy.equalsIgnoreCase("time")) {
      entities = sortEntities(entities, sortBy);
    }
    return knowledgeBaseMapper.toListItemDTOList(entities);
  }

  public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
    return listKnowledgeBases(null, null);
  }

  public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
    return findEntityByUserAndId(id).map(knowledgeBaseMapper::toListItemDTO);
  }

  public Optional<KnowledgeBaseEntity> getKnowledgeBaseEntity(Long id) {
    return findEntityByUserAndId(id);
  }

  public List<String> getKnowledgeBaseNames(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    Map<Long, String> nameMap = getKnowledgeBaseNameMap(ids);
    return ids.stream()
      .map(id -> nameMap.getOrDefault(id, "?????"))
      .toList();
  }

  public Map<Long, String> getKnowledgeBaseNameMap(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return Map.of();
    }
    List<Long> uniqueIds = ids.stream().distinct().toList();
    return EntityQueries.listByUserIdAndIdIn(
        knowledgeBaseEntityMapper, UserContext.requireUserId(), uniqueIds,
        KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId)
      .stream()
      .collect(Collectors.toMap(KnowledgeBaseEntity::getId, KnowledgeBaseEntity::getName));
  }

  public List<String> getAllCategories() {
    return knowledgeBaseEntityMapper.selectCategoriesByUserId(UserContext.requireUserId());
  }

  public List<KnowledgeBaseListItemDTO> listByCategory(String category) {
    Long userId = UserContext.requireUserId();
    List<KnowledgeBaseEntity> entities;
    if (category == null || category.isBlank()) {
      entities = knowledgeBaseEntityMapper.selectList(
        Wrappers.<KnowledgeBaseEntity>lambdaQuery()
          .eq(KnowledgeBaseEntity::getUserId, userId)
          .isNull(KnowledgeBaseEntity::getCategory)
          .orderByDesc(KnowledgeBaseEntity::getUploadedAt));
    } else {
      entities = knowledgeBaseEntityMapper.selectList(
        Wrappers.<KnowledgeBaseEntity>lambdaQuery()
          .eq(KnowledgeBaseEntity::getUserId, userId)
          .eq(KnowledgeBaseEntity::getCategory, category)
          .orderByDesc(KnowledgeBaseEntity::getUploadedAt));
    }
    return knowledgeBaseMapper.toListItemDTOList(entities);
  }

  @Transactional
  public void updateCategory(Long id, String category) {
    KnowledgeBaseEntity entity = findEntityByUserAndId(id)
      .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "??????"));
    entity.setCategory(category != null && !category.isBlank() ? category : null);
    MapperUtils.save(knowledgeBaseEntityMapper, entity);
    log.info("???????: id={}, category={}", id, category);
  }

  public List<KnowledgeBaseListItemDTO> search(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return listKnowledgeBases();
    }
    String kw = keyword.trim();
    Long userId = UserContext.requireUserId();
    List<KnowledgeBaseEntity> entities = knowledgeBaseEntityMapper.selectList(
      Wrappers.<KnowledgeBaseEntity>lambdaQuery()
        .eq(KnowledgeBaseEntity::getUserId, userId)
        .and(w -> w.like(KnowledgeBaseEntity::getName, kw)
          .or().like(KnowledgeBaseEntity::getOriginalFilename, kw)
          .or().like(KnowledgeBaseEntity::getDescription, kw))
        .orderByDesc(KnowledgeBaseEntity::getUploadedAt));
    return knowledgeBaseMapper.toListItemDTOList(entities);
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
    long userMessages = ragChatMessageMapper.countByTypeAndSessionUserId(
      MessageType.USER.name(), userId);
    return new KnowledgeBaseStatsDTO(totalDocs, userMessages, totalAccess, vectorStored, processing);
  }

  public byte[] downloadFile(Long id) {
    KnowledgeBaseEntity entity = findEntityByUserAndId(id)
      .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "??????"));
    String storageKey = entity.getStorageKey();
    if (storageKey == null || storageKey.isBlank()) {
      throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "?????????");
    }
    log.info("???????: id={}, filename={}", id, entity.getOriginalFilename());
    return fileStorageService.downloadFile(storageKey);
  }

  public KnowledgeBaseEntity getEntityForDownload(Long id) {
    return findEntityByUserAndId(id)
      .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "??????"));
  }

  private Optional<KnowledgeBaseEntity> findEntityByUserAndId(Long id) {
    return EntityQueries.byUserAndId(
      knowledgeBaseEntityMapper, UserContext.requireUserId(), id,
      KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId);
  }
}
