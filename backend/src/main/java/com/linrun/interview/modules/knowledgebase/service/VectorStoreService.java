package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * 向量存储服务接口（对齐业界实践 VectorStoreService）。
 *
 * <p>统一负责文本嵌入、向量写入 ES、向量删除（按 docId / docId+versionId / embeddingId）。
 */
public interface VectorStoreService {

    /**
     * 批量嵌入并写入 ES，返回 ES 分配的 embeddingId 列表（顺序与入参一致）。
     */
    List<String> embedAndStore(List<KnowledgeBaseSegmentEntity> segments);

    /**
     * 单条嵌入并写入 ES，返回 embeddingId。
     */
    String embedAndStore(KnowledgeBaseSegmentEntity segment);

    /**
     * 按 embeddingId 删除单条向量（失败仅告警，不抛异常）。
     */
    void remove(String embeddingId);

    /**
     * 按 embeddingId 集合批量删除向量（向量化批次 DB 回写失败时的反向补偿，失败抛异常）。
     */
    void removeByEmbeddingIds(List<String> embeddingIds);

    /**
     * 按 docId 删除该文档所有版本的向量（metadata DOC_ID filter）。
     */
    void removeByDocId(Long docId);

    /**
     * 按 docIds 批量删除向量（metadata DOC_ID in filter）。
     */
    void removeByDocIds(List<Long> docIds);

    /**
     * 按 docId + versionId 删除指定版本的向量（DOC_ID + VERSION 双 filter）。
     */
    void removeByDocIdAndVersion(Long docId, Long versionId);

    /**
     * 把分段实体转为 LC4j {@link TextSegment}（含 metadata）。
     */
    TextSegment toTextSegment(KnowledgeBaseSegmentEntity segment);
}
