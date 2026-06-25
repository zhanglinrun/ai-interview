package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.constant.SegmentStatus;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 知识库分段 Service（对齐 know-engine KnowledgeSegmentService）。
 *
 * <p>负责分段落库、分页查询待向量化分段、按版本/文档删除分段、回写 embeddingId 与状态。
 */
public interface KnowledgeSegmentService {

    /**
     * 批量保存分段（split 落库用）。
     */
    List<KnowledgeBaseSegmentEntity> saveBatch(List<KnowledgeBaseSegmentEntity> segments);

    /**
     * 分页查待向量化的分段（状态 STORED + skipEmbedding=0 + embeddingId 为空）。
     */
    Page<KnowledgeBaseSegmentEntity> pagePendingEmbedding(
        Long docId, Long versionId, SegmentStatus status, Pageable pageable);

    /**
     * 按 docId 查所有分段（顺序排列）。
     */
    List<KnowledgeBaseSegmentEntity> findByDocumentId(Long docId);

    /**
     * 按 versionId 查所有分段。
     */
    List<KnowledgeBaseSegmentEntity> findByVersionId(Long versionId);

    /**
     * 按 docId 物理删除所有分段。
     */
    int physicalDeleteByDocumentId(Long docId);

    /**
     * 按 versionId 物理删除所有分段。
     */
    int physicalDeleteByDocumentVersion(Long versionId);

    /**
     * 更新分段（回写 embeddingId + 状态）。
     */
    void update(KnowledgeBaseSegmentEntity segment);

    /**
     * 按 versionId 统计分段数。
     */
    long countByDocumentVersion(Long versionId);

    /**
     * 失效版本时批量降级分段状态（VECTOR_STORED → STORED + 清空 embeddingId）。
     */
    int downgradeStatus(Long docId, Long versionId,
        interview.guide.modules.knowledgebase.constant.SegmentStatus fromStatus,
        interview.guide.modules.knowledgebase.constant.SegmentStatus toStatus);

    /**
     * 按文档 ID + 非当前版本 统计残留分段数（补偿任务用）。
     */
    long countStaleByDocumentId(Long docId, Long currentVersionId);
}
