package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseVersionEntity;

import java.util.List;
import java.util.Optional;

/**
 * 知识库版本 Service（对齐业界实践 KnowledgeDocumentVersionService）。
 */
public interface KnowledgeDocumentVersionService {

    /** 保存版本。 */
    KnowledgeBaseVersionEntity save(KnowledgeBaseVersionEntity version);

    /** 按 versionId 查。 */
    Optional<KnowledgeBaseVersionEntity> getById(Long versionId);

    /** 按 docId 查所有版本（降序，最新在前）。 */
    List<KnowledgeBaseVersionEntity> listByDocId(Long docId);

    /** 按 docId 查最新版本。 */
    Optional<KnowledgeBaseVersionEntity> findLatestByDocId(Long docId);

    /** 按内容哈希 + 上传用户查（按用户隔离的跨文档跨版本去重，避免跨用户互相阻断上传并泄漏他人文档存在性）。 */
    Optional<KnowledgeBaseVersionEntity> findByContentHash(String contentHash, Long userId);

    /** 按 docId + version 精确查（版本号唯一性）。 */
    Optional<KnowledgeBaseVersionEntity> findByDocIdAndVersion(Long docId, String version);

    /** 更新版本（状态切换等）。 */
    void update(KnowledgeBaseVersionEntity version);

    /** 将当前已向量化版本原子降为 CONVERTED，供重新切块使用。 */
    boolean beginRechunk(Long versionId, Long docId);

    /** 按 docId 物理删除所有版本。 */
    int physicalDeleteByDocId(Long docId);

    /** 按 versionId 物理删除单个版本（旧版本清理）。 */
    int physicalDeleteByVersionId(Long versionId);

    /** 按状态查所有版本（补偿任务扫 CHUNKED）。 */
    List<KnowledgeBaseVersionEntity> findByStatus(
        com.linrun.interview.modules.knowledgebase.constant.DocumentStatus status);
}
