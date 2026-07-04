package com.linrun.interview.modules.knowledgebase.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.modules.knowledgebase.constant.SegmentStatus;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseSegmentMapper;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSegmentServiceImpl implements KnowledgeSegmentService {

  private static final Duration PARENT_CHUNK_CACHE_TTL = Duration.ofSeconds(30);
  private static final String PARENT_CHUNK_CACHE_PREFIX = "kb:parent-chunk:";

  private final KnowledgeBaseSegmentMapper segmentMapper;
  private final StringRedisTemplate stringRedisTemplate;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional
  public List<KnowledgeBaseSegmentEntity> saveBatch(List<KnowledgeBaseSegmentEntity> segments) {
    if (segments == null || segments.isEmpty()) {
      return List.of();
    }
    LocalDateTime now = LocalDateTime.now();
    for (KnowledgeBaseSegmentEntity segment : segments) {
      if (segment.getCreatedAt() == null) {
        segment.setCreatedAt(now);
      }
      if (segment.getUpdatedAt() == null) {
        segment.setUpdatedAt(now);
      }
      MapperUtils.save(segmentMapper, segment);
    }
    log.info("批量保存分段完成: count={}", segments.size());
    return segments;
  }

  @Override
  public List<KnowledgeBaseSegmentEntity> listPendingEmbedding(
      Long docId, Long versionId, SegmentStatus status, int limit) {
    return segmentMapper.selectList(
      Wrappers.<KnowledgeBaseSegmentEntity>lambdaQuery()
        .eq(KnowledgeBaseSegmentEntity::getDocumentId, docId)
        .eq(KnowledgeBaseSegmentEntity::getDocumentVersion, versionId)
        .eq(KnowledgeBaseSegmentEntity::getStatus, status)
        .eq(KnowledgeBaseSegmentEntity::getSkipEmbedding, 0)
        .isNull(KnowledgeBaseSegmentEntity::getEmbeddingId)
        .orderByAsc(KnowledgeBaseSegmentEntity::getChunkOrder)
        .last("LIMIT " + Math.max(limit, 1)));
  }

  @Override
  public List<KnowledgeBaseSegmentEntity> findByDocumentId(Long docId) {
    return segmentMapper.selectList(
      Wrappers.<KnowledgeBaseSegmentEntity>lambdaQuery()
        .eq(KnowledgeBaseSegmentEntity::getDocumentId, docId)
        .orderByAsc(KnowledgeBaseSegmentEntity::getChunkOrder));
  }

  @Override
  public List<KnowledgeBaseSegmentEntity> findByVersionId(Long versionId) {
    return segmentMapper.selectList(
      Wrappers.<KnowledgeBaseSegmentEntity>lambdaQuery()
        .eq(KnowledgeBaseSegmentEntity::getDocumentVersion, versionId)
        .orderByAsc(KnowledgeBaseSegmentEntity::getChunkOrder));
  }

  @Override
  @Transactional
  public int physicalDeleteByDocumentId(Long docId) {
    int deleted = segmentMapper.physicalDeleteByDocumentId(docId);
    log.info("按docId物理删除分段: docId={}, deleted={}", docId, deleted);
    return deleted;
  }

  @Override
  @Transactional
  public int physicalDeleteByDocumentVersion(Long versionId) {
    int deleted = segmentMapper.physicalDeleteByDocumentVersion(versionId);
    log.info("按versionId物理删除分段: versionId={}, deleted={}", versionId, deleted);
    return deleted;
  }

  @Override
  @Transactional
  public void update(KnowledgeBaseSegmentEntity segment) {
    MapperUtils.save(segmentMapper, segment);
  }

  @Override
  @Transactional
  public int batchUpdateEmbedding(List<KnowledgeBaseSegmentEntity> segments) {
    if (segments == null || segments.isEmpty()) {
      return 0;
    }
    int affected = segmentMapper.batchUpdateEmbedding(segments, SegmentStatus.VECTOR_STORED.name());
    log.info("批量回写分段 embeddingId 完成: count={}, affected={}", segments.size(), affected);
    return affected;
  }

  @Override
  public long countWithEmbedding(Long docId, Long versionId) {
    return segmentMapper.selectCount(
      Wrappers.<KnowledgeBaseSegmentEntity>lambdaQuery()
        .eq(KnowledgeBaseSegmentEntity::getDocumentId, docId)
        .eq(KnowledgeBaseSegmentEntity::getDocumentVersion, versionId)
        .isNotNull(KnowledgeBaseSegmentEntity::getEmbeddingId));
  }

  @Override
  public long countByDocumentVersion(Long versionId) {
    return segmentMapper.selectCount(
      Wrappers.<KnowledgeBaseSegmentEntity>lambdaQuery()
        .eq(KnowledgeBaseSegmentEntity::getDocumentVersion, versionId));
  }

  @Override
  @Transactional
  public int downgradeStatus(Long docId, Long versionId, SegmentStatus fromStatus, SegmentStatus toStatus) {
    int affected = segmentMapper.downgradeStatus(
      docId, versionId, fromStatus.name(), toStatus.name());
    log.info("降级分段状态: docId={}, versionId={}, {}->{}, affected={}",
        docId, versionId, fromStatus, toStatus, affected);
    return affected;
  }

  @Override
  public long countStaleByDocumentId(Long docId, Long currentVersionId) {
    return segmentMapper.selectCount(
      Wrappers.<KnowledgeBaseSegmentEntity>lambdaQuery()
        .eq(KnowledgeBaseSegmentEntity::getDocumentId, docId)
        .ne(KnowledgeBaseSegmentEntity::getDocumentVersion, currentVersionId));
  }

  @Override
  public List<KnowledgeBaseSegmentEntity> findByChunkIdIn(List<String> chunkIds) {
    if (chunkIds == null || chunkIds.isEmpty()) {
      return List.of();
    }
    List<KnowledgeBaseSegmentEntity> result = new ArrayList<>();
    List<String> missed = new ArrayList<>();
    for (String chunkId : chunkIds) {
      KnowledgeBaseSegmentEntity cached = readParentChunkCache(chunkId);
      if (cached != null) {
        result.add(cached);
      } else {
        missed.add(chunkId);
      }
    }
    if (!missed.isEmpty()) {
      List<KnowledgeBaseSegmentEntity> loaded = segmentMapper.selectList(
        Wrappers.<KnowledgeBaseSegmentEntity>lambdaQuery()
          .in(KnowledgeBaseSegmentEntity::getChunkId, missed));
      for (KnowledgeBaseSegmentEntity segment : loaded) {
        writeParentChunkCache(segment.getChunkId(), segment);
        result.add(segment);
      }
    }
    return result;
  }

  @Override
  public List<KnowledgeBaseSegmentEntity> findByBrotherChunkIdIn(List<String> brotherChunkIds) {
    if (brotherChunkIds == null || brotherChunkIds.isEmpty()) {
      return List.of();
    }
    return segmentMapper.selectList(
      Wrappers.<KnowledgeBaseSegmentEntity>lambdaQuery()
        .in(KnowledgeBaseSegmentEntity::getBrotherChunkId, brotherChunkIds)
        .orderByAsc(KnowledgeBaseSegmentEntity::getBrotherChunkId)
        .orderByAsc(KnowledgeBaseSegmentEntity::getBrotherChunkIndex));
  }

  private KnowledgeBaseSegmentEntity readParentChunkCache(String chunkId) {
    try {
      String json = stringRedisTemplate.opsForValue().get(PARENT_CHUNK_CACHE_PREFIX + chunkId);
      if (json == null || json.isBlank()) {
        return null;
      }
      return objectMapper.readValue(json, KnowledgeBaseSegmentEntity.class);
    } catch (Exception e) {
      log.debug("读取 parent chunk 缓存失败: chunkId={}", chunkId, e);
      return null;
    }
  }

  private void writeParentChunkCache(String chunkId, KnowledgeBaseSegmentEntity segment) {
    if (chunkId == null || chunkId.isBlank() || segment == null) {
      return;
    }
    try {
      stringRedisTemplate.opsForValue().set(
        PARENT_CHUNK_CACHE_PREFIX + chunkId,
        objectMapper.writeValueAsString(segment),
        PARENT_CHUNK_CACHE_TTL);
    } catch (JsonProcessingException e) {
      log.debug("写入 parent chunk 缓存失败: chunkId={}", chunkId, e);
    }
  }

  @Override
  public KnowledgeBaseSegmentEntity findById(Long segmentId) {
    if (segmentId == null) {
      return null;
    }
    return segmentMapper.selectById(segmentId);
  }

  @Override
  public Page<KnowledgeBaseSegmentEntity> pageByDocument(Long docId, Long versionId, int page, int size) {
    Page<KnowledgeBaseSegmentEntity> pageReq = new Page<>(Math.max(page, 1), Math.max(size, 1));
    var wrapper = Wrappers.<KnowledgeBaseSegmentEntity>lambdaQuery()
        .eq(KnowledgeBaseSegmentEntity::getDocumentId, docId)
        .orderByAsc(KnowledgeBaseSegmentEntity::getChunkOrder);
    if (versionId != null) {
      wrapper.eq(KnowledgeBaseSegmentEntity::getDocumentVersion, versionId);
    }
    return segmentMapper.selectPage(pageReq, wrapper);
  }

  @Override
  public long countByDocument(Long docId, Long versionId) {
    var wrapper = Wrappers.<KnowledgeBaseSegmentEntity>lambdaQuery()
        .eq(KnowledgeBaseSegmentEntity::getDocumentId, docId);
    if (versionId != null) {
      wrapper.eq(KnowledgeBaseSegmentEntity::getDocumentVersion, versionId);
    }
    return segmentMapper.selectCount(wrapper);
  }
}
