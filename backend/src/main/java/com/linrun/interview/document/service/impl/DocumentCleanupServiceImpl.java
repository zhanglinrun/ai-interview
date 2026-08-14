package com.linrun.interview.document.service.impl;
import com.linrun.interview.document.service.DocumentCleanupService;
import com.linrun.interview.document.service.KnowledgeDocumentVersionService;
import com.linrun.interview.document.service.KnowledgeSegmentService;
import com.linrun.interview.document.service.VectorStoreService;
import com.linrun.interview.rag.service.EvidenceSnapshotService;


import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.document.service.FileStorageService;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 文档版本清理服务实现（对齐业界实践 DocumentCleanupServiceImpl）。
 *
 * <p>清理旧版本：删 ES 向量（按 docId+versionId filter）+ 物理删该版本分段和版本记录，
 * 同时清理 Redis 上的上下文扩展缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentCleanupServiceImpl implements DocumentCleanupService {

    private final VectorStoreService vectorStoreService;
    private final KnowledgeSegmentService segmentService;
    private final KnowledgeDocumentVersionService versionService;
    private final KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;
    private final FileStorageService fileStorageService;
    private final SegmentTextCacheService segmentTextCacheService;
    private final EvidenceSnapshotService evidenceSnapshotService;

    @Override
    public boolean cleanupOldVersionData(Long docId, Long versionId) {
        log.info("清理旧版本数据: docId={}, versionId={}", docId, versionId);
        KnowledgeBaseVersionEntity version = versionService.findById(versionId).orElse(null);
        KnowledgeBaseEntity document = knowledgeBaseEntityMapper.selectById(docId);
        // 1. 删 ES 向量
        vectorStoreService.removeByDocIdAndVersion(docId, versionId);
        // 2. 物理删该版本 segment
        segmentService.physicalDeleteByDocumentVersion(versionId);
        // 3. 物理删该版本记录
        versionService.physicalDeleteByVersionId(versionId);
        segmentTextCacheService.evictAll();
        segmentService.evictExpansionCache();
        if (document != null) {
            evidenceSnapshotService.markSourceUnavailable(
                document.getUserId(), DataDomain.CANDIDATE,
                String.valueOf(docId), String.valueOf(versionId));
        }
        if (version != null && version.getStorageKey() != null
            && !version.getStorageKey().isBlank()) {
            fileStorageService.deleteKnowledgeBase(version.getStorageKey());
        }
        log.info("清理旧版本数据完成: docId={}, versionId={}", docId, versionId);
        return true;
    }
}
