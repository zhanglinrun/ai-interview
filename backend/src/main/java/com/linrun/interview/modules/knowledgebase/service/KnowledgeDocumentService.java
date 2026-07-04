package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.modules.knowledgebase.constant.DocumentStatus;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseVersionEntity;

import java.util.List;

/**
 * 知识库文档管理接口（对齐业界实践 KnowledgeDocumentService）。
 *
 * <p>负责文档级管理：删除级联（ES 向量 + segment + version + 文档）、版本激活/失效。
 * 向量化执行 {@link #activateVersion} 由 {@link DocumentProcessService#embedAndStore} 或
 * 事件监听器/补偿任务调用。
 */
public interface KnowledgeDocumentService {

    /**
     * 删除知识库（级联）：按 docId 删 ES 向量 → 物理删 segment → 物理删 version → 物理删文档。
     *
     * @param docId 知识库 ID
     */
    void removeDocumentWithSegments(Long docId);

    /**
     * 批量删除知识库（级联）。
     *
     * @param docIds 知识库 ID 列表
     */
    void removeDocumentsWithSegments(List<Long> docIds);

    /**
     * 激活版本：分页扫 STORED + skipEmbedding=0 + embeddingId IS NULL 的 segment，
     * 嵌入写 ES，回写 embeddingId + 升 VECTOR_STORED，版本升 VECTOR_STORED，文档主表升 VECTOR_STORED。
     *
     * @param versionId 版本 ID
     */
    void activateVersion(Long versionId);

    /**
     * 失效版本：清 ES 向量（按 docId+versionId filter）→ segment 降 STORED → 版本降 CHUNKED。
     *
     * @param versionId 版本 ID
     */
    void deactivateVersion(Long versionId);

    /**
     * 按版本实体激活（供事件监听器/补偿任务调用，避免再查一次）。
     *
     * @param version 版本实体
     */
    void activateVersion(KnowledgeBaseVersionEntity version);

    /**
     * 单调推进文档主表与版本表状态（对齐业界实践 advanceDocumentAndVersionStatus）。
     *
     * @return 主表或版本表至少有一处被更新时返回 true
     */
    boolean advanceDocumentAndVersionStatus(Long docId, Long versionId, DocumentStatus targetStatus);

    /**
     * 热切换激活版本（目标版本 ES 向量已存在）：一个事务内完成
     * 旧版本 DB 降级（segment 降 STORED + 版本降 CHUNKED）+ 主表指针/状态更新，
     * 旧版本 ES 向量清理注册到事务提交后执行（事务内禁止外部 API），
     * 清理失败留给向量对账任务兜底。
     *
     * @param docId           知识库 ID
     * @param targetVersionId 目标版本 ID（须已 VECTOR_STORED）
     */
    void switchActiveVersion(Long docId, Long targetVersionId);
}
