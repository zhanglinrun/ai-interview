package interview.guide.modules.knowledgebase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档版本清理服务实现（对齐 know-engine DocumentCleanupServiceImpl）。
 *
 * <p>清理旧版本：删 ES 向量（按 docId+versionId filter）+ 物理删该版本 segment + 物理删版本记录。
 * 比 know-engine 多了物理删 segment 和版本记录（know-engine 仅删向量，DB 残留靠别的流程）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentCleanupServiceImpl implements DocumentCleanupService {

    private final VectorStoreService vectorStoreService;
    private final KnowledgeSegmentService segmentService;
    private final KnowledgeDocumentVersionService versionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cleanupOldVersionData(Long docId, Long versionId) {
        log.info("清理旧版本数据: docId={}, versionId={}", docId, versionId);
        // 1. 删 ES 向量
        vectorStoreService.removeByDocIdAndVersion(docId, versionId);
        // 2. 物理删该版本 segment
        segmentService.physicalDeleteByDocumentVersion(versionId);
        // 3. 物理删该版本记录
        versionService.physicalDeleteByVersionId(versionId);
        log.info("清理旧版本数据完成: docId={}, versionId={}", docId, versionId);
        return true;
    }
}
