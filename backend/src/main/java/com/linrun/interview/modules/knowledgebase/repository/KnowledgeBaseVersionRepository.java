package com.linrun.interview.modules.knowledgebase.repository;

import com.linrun.interview.modules.knowledgebase.constant.DocumentStatus;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库版本 Repository（对齐 know-engine KnowledgeDocumentVersionService/Mapper）。
 */
@Repository
public interface KnowledgeBaseVersionRepository extends JpaRepository<KnowledgeBaseVersionEntity, Long> {

    /** 按文档 ID 查所有版本（降序，最新在前）。 */
    List<KnowledgeBaseVersionEntity> findByDocIdOrderByVersionIdDesc(Long docId);

    /** 按文档 ID 查最新版本。 */
    Optional<KnowledgeBaseVersionEntity> findFirstByDocIdOrderByVersionIdDesc(Long docId);

    /** 按内容哈希去重（跨文档跨版本）。 */
    Optional<KnowledgeBaseVersionEntity> findByContentHash(String contentHash);

    /** 按 docId + version 精确查（版本号唯一性约束）。 */
    Optional<KnowledgeBaseVersionEntity> findByDocIdAndVersion(Long docId, String version);

    /** 按状态查所有版本（补偿任务扫 CHUNKED 重试）。 */
    List<KnowledgeBaseVersionEntity> findByStatus(DocumentStatus status);

    /** 按 docId 物理删除所有版本（删除知识库级联，绕过逻辑删除——JPA 无 @TableLogic，直接 delete）。 */
    @Modifying
    @Query("delete from KnowledgeBaseVersionEntity v where v.docId = :docId")
    int physicalDeleteByDocId(@Param("docId") Long docId);

    /** 按 versionId 物理删除单个版本（旧版本清理）。 */
    @Modifying
    @Query("delete from KnowledgeBaseVersionEntity v where v.versionId = :versionId")
    int physicalDeleteByVersionId(@Param("versionId") Long versionId);
}
