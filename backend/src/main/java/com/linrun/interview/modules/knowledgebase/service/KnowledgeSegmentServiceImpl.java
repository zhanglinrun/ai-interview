package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.modules.knowledgebase.constant.SegmentStatus;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import com.linrun.interview.modules.knowledgebase.repository.KnowledgeBaseSegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识库分段 Service 实现（对齐 know-engine KnowledgeSegmentServiceImpl）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSegmentServiceImpl implements KnowledgeSegmentService {

    private final KnowledgeBaseSegmentRepository segmentRepository;

    @Override
    @Transactional
    public List<KnowledgeBaseSegmentEntity> saveBatch(List<KnowledgeBaseSegmentEntity> segments) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        List<KnowledgeBaseSegmentEntity> saved = segmentRepository.saveAll(segments);
        log.info("批量保存分段完成: count={}", saved.size());
        return saved;
    }

    @Override
    public Page<KnowledgeBaseSegmentEntity> pagePendingEmbedding(
        Long docId, Long versionId, SegmentStatus status, Pageable pageable) {
        return segmentRepository.pagePendingEmbedding(docId, versionId, status, pageable);
    }

    @Override
    public List<KnowledgeBaseSegmentEntity> findByDocumentId(Long docId) {
        return segmentRepository.findByDocumentIdOrderByChunkOrderAsc(docId);
    }

    @Override
    public List<KnowledgeBaseSegmentEntity> findByVersionId(Long versionId) {
        return segmentRepository.findByDocumentVersionOrderByChunkOrderAsc(versionId);
    }

    @Override
    @Transactional
    public int physicalDeleteByDocumentId(Long docId) {
        int deleted = segmentRepository.physicalDeleteByDocumentId(docId);
        log.info("按docId物理删除分段: docId={}, deleted={}", docId, deleted);
        return deleted;
    }

    @Override
    @Transactional
    public int physicalDeleteByDocumentVersion(Long versionId) {
        int deleted = segmentRepository.physicalDeleteByDocumentVersion(versionId);
        log.info("按versionId物理删除分段: versionId={}, deleted={}", versionId, deleted);
        return deleted;
    }

    @Override
    @Transactional
    public void update(KnowledgeBaseSegmentEntity segment) {
        segmentRepository.save(segment);
    }

    @Override
    public long countByDocumentVersion(Long versionId) {
        return segmentRepository.countByDocumentVersion(versionId);
    }

    @Override
    @Transactional
    public int downgradeStatus(Long docId, Long versionId,
        com.linrun.interview.modules.knowledgebase.constant.SegmentStatus fromStatus,
        com.linrun.interview.modules.knowledgebase.constant.SegmentStatus toStatus) {
        int affected = segmentRepository.downgradeStatus(docId, versionId, fromStatus, toStatus);
        log.info("降级分段状态: docId={}, versionId={}, {}->{}, affected={}",
            docId, versionId, fromStatus, toStatus, affected);
        return affected;
    }

    @Override
    public long countStaleByDocumentId(Long docId, Long currentVersionId) {
        return segmentRepository.countStaleByDocumentId(docId, currentVersionId);
    }

    @Override
    public List<KnowledgeBaseSegmentEntity> findByChunkIdIn(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        return segmentRepository.findByChunkIdIn(chunkIds);
    }

    @Override
    public List<KnowledgeBaseSegmentEntity> findByBrotherChunkIdIn(List<String> brotherChunkIds) {
        if (brotherChunkIds == null || brotherChunkIds.isEmpty()) {
            return List.of();
        }
        return segmentRepository
            .findByBrotherChunkIdInOrderByBrotherChunkIdAscBrotherChunkIndexAsc(brotherChunkIds);
    }
}
