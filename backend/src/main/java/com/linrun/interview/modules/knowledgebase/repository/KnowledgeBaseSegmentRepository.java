package com.linrun.interview.modules.knowledgebase.repository;

import com.linrun.interview.modules.knowledgebase.constant.SegmentStatus;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 知识库分段 Repository（对齐 know-engine KnowledgeSegmentService/Mapper）。
 */
@Repository
public interface KnowledgeBaseSegmentRepository extends JpaRepository<KnowledgeBaseSegmentEntity, Long> {

    /** 按文档 ID 查所有分段（顺序排列）。 */
    List<KnowledgeBaseSegmentEntity> findByDocumentIdOrderByChunkOrderAsc(Long documentId);

    /** 按版本 ID 查所有分段。 */
    List<KnowledgeBaseSegmentEntity> findByDocumentVersionOrderByChunkOrderAsc(Long documentVersion);

    /** 按文档 ID 统计分段数。 */
    long countByDocumentId(Long documentId);

    /** 按版本 ID 统计分段数。 */
    long countByDocumentVersion(Long documentVersion);

    /**
     * 分页查待向量化的分段（状态 STORED + skipEmbedding=0 + embeddingId 为空），
     * 供 activateVersion 分页扫描批 100 嵌入。
     */
    @Query("select s from KnowledgeBaseSegmentEntity s "
        + "where s.documentId = :docId and s.documentVersion = :versionId "
        + "and s.status = :status and s.skipEmbedding = 0 and s.embeddingId is null "
        + "order by s.chunkOrder asc")
    Page<KnowledgeBaseSegmentEntity> pagePendingEmbedding(
        @Param("docId") Long docId,
        @Param("versionId") Long versionId,
        @Param("status") SegmentStatus status,
        Pageable pageable);

    /** 按 docId 物理删除所有分段（删除知识库级联）。 */
    @Modifying
    @Query("delete from KnowledgeBaseSegmentEntity s where s.documentId = :docId")
    int physicalDeleteByDocumentId(@Param("docId") Long docId);

    /** 按 versionId 物理删除所有分段（版本切换/失效清理）。 */
    @Modifying
    @Query("delete from KnowledgeBaseSegmentEntity s where s.documentVersion = :versionId")
    int physicalDeleteByDocumentVersion(@Param("versionId") Long versionId);

    /** 失效版本时批量降级：VECTOR_STORED → STORED + 清空 embeddingId（按 docId + versionId）。 */
    @Modifying
    @Query("update KnowledgeBaseSegmentEntity s set s.status = :toStatus, s.embeddingId = null "
        + "where s.documentId = :docId and s.documentVersion = :versionId "
        + "and s.status = :fromStatus")
    int downgradeStatus(
        @Param("docId") Long docId,
        @Param("versionId") Long versionId,
        @Param("fromStatus") SegmentStatus fromStatus,
        @Param("toStatus") SegmentStatus toStatus);

    /** 按文档 ID + 非指定版本 统计残留分段数（补偿任务扫需清理的文档）。 */
    @Query("select count(s) from KnowledgeBaseSegmentEntity s "
        + "where s.documentId = :docId and s.documentVersion <> :currentVersionId")
    long countStaleByDocumentId(
        @Param("docId") Long docId,
        @Param("currentVersionId") Long currentVersionId);

    /**
     * 按 chunkId 集合查分段（父子扩展：用命中 chunk 的 parentChunkId 取父块文本）。
     */
    List<KnowledgeBaseSegmentEntity> findByChunkIdIn(List<String> chunkIds);

    /**
     * 按 brotherChunkId 集合查同组兄弟分段（兄弟扩展：按 brotherChunkIndex 顺序拼接成完整段落）。
     */
    List<KnowledgeBaseSegmentEntity> findByBrotherChunkIdInOrderByBrotherChunkIdAscBrotherChunkIndexAsc(
        List<String> brotherChunkIds);
}
