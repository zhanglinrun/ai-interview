package com.linrun.interview.modules.knowledgebase.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.modules.knowledgebase.constant.DocumentStatus;
import com.linrun.interview.modules.knowledgebase.constant.SegmentStatus;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseVersionMapper;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentVersionServiceImpl implements KnowledgeDocumentVersionService {

  private final KnowledgeBaseVersionMapper versionMapper;

  @Override
  @Transactional
  public KnowledgeBaseVersionEntity save(KnowledgeBaseVersionEntity version) {
    return MapperUtils.save(versionMapper, version);
  }

  @Override
  public Optional<KnowledgeBaseVersionEntity> getById(Long versionId) {
    return Optional.ofNullable(versionMapper.selectById(versionId));
  }

  @Override
  public List<KnowledgeBaseVersionEntity> listByDocId(Long docId) {
    return versionMapper.selectList(
      Wrappers.<KnowledgeBaseVersionEntity>lambdaQuery()
        .eq(KnowledgeBaseVersionEntity::getDocId, docId)
        .orderByDesc(KnowledgeBaseVersionEntity::getVersionId));
  }

  @Override
  public Optional<KnowledgeBaseVersionEntity> findLatestByDocId(Long docId) {
    return Optional.ofNullable(versionMapper.selectOne(
      Wrappers.<KnowledgeBaseVersionEntity>lambdaQuery()
        .eq(KnowledgeBaseVersionEntity::getDocId, docId)
        .orderByDesc(KnowledgeBaseVersionEntity::getVersionId)
        .last("LIMIT 1")));
  }

  @Override
  public Optional<KnowledgeBaseVersionEntity> findByContentHash(String contentHash, Long userId) {
    return Optional.ofNullable(versionMapper.selectOne(
      Wrappers.<KnowledgeBaseVersionEntity>lambdaQuery()
        .eq(KnowledgeBaseVersionEntity::getContentHash, contentHash)
        .eq(KnowledgeBaseVersionEntity::getUploadUser, String.valueOf(userId))
        .last("LIMIT 1")));
  }

  @Override
  public Optional<KnowledgeBaseVersionEntity> findByDocIdAndVersion(Long docId, String version) {
    return Optional.ofNullable(versionMapper.selectOne(
      Wrappers.<KnowledgeBaseVersionEntity>lambdaQuery()
        .eq(KnowledgeBaseVersionEntity::getDocId, docId)
        .eq(KnowledgeBaseVersionEntity::getVersion, version)
        .last("LIMIT 1")));
  }

  @Override
  @Transactional
  public void update(KnowledgeBaseVersionEntity version) {
    MapperUtils.save(versionMapper, version);
  }

  @Override
  @Transactional
  public boolean beginRechunk(Long versionId, Long docId) {
    return versionMapper.beginRechunk(versionId, docId) == 1;
  }

  @Override
  @Transactional
  public int physicalDeleteByDocId(Long docId) {
    int deleted = versionMapper.physicalDeleteByDocId(docId);
    log.info("按docId物理删除版本: docId={}, deleted={}", docId, deleted);
    return deleted;
  }

  @Override
  @Transactional
  public int physicalDeleteByVersionId(Long versionId) {
    int deleted = versionMapper.physicalDeleteByVersionId(versionId);
    log.info("按versionId物理删除版本: versionId={}, deleted={}", versionId, deleted);
    return deleted;
  }

  @Override
  public List<KnowledgeBaseVersionEntity> findByStatus(DocumentStatus status) {
    return versionMapper.selectList(
      Wrappers.<KnowledgeBaseVersionEntity>lambdaQuery()
        .eq(KnowledgeBaseVersionEntity::getStatus, status));
  }
}
