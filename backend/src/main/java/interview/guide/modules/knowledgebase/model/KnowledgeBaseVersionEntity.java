package interview.guide.modules.knowledgebase.model;

import interview.guide.modules.knowledgebase.constant.DocumentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 知识库文档版本实体（对齐 know-engine KnowledgeDocumentVersion）。
 *
 * <p>与 {@link KnowledgeBaseEntity} 一对多：一个知识库可有多个版本，每个版本独立存储原始文件 URL、
 * 转换后 Markdown URL、内容哈希与状态。{@link KnowledgeBaseEntity#getCurrentVersionId()} 指向当前激活版本。
 */
@Entity
@Table(name = "knowledge_base_version", indexes = {
    @Index(name = "idx_kbv_doc_id", columnList = "docId"),
    @Index(name = "idx_kbv_doc_version", columnList = "docId,version")
})
public class KnowledgeBaseVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long versionId;

    /** 关联知识库 ID（knowledge_bases.id）。 */
    @Column(nullable = false)
    private Long docId;

    /** 语义化版本号，如 1.0.0。 */
    @Column(nullable = false, length = 32)
    private String version;

    /** 原始文件 URL（RustFS）。 */
    @Column(length = 1000)
    private String docUrl;

    /** 转换后 Markdown 文件 URL（RustFS，可选；若为空则用 convertedContent）。 */
    @Column(length = 1000)
    private String convertedDocUrl;

    /** 转换后 Markdown 文本内容（解析产物，split 时直接取，省存储往返）。 */
    @Lob
    private String convertedContent;

    /** 文档内容 SHA-256（跨版本去重）。 */
    @Column(length = 64)
    private String contentHash;

    /** 版本状态机。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DocumentStatus status;

    /** 上传用户标识。 */
    @Column(length = 64)
    private String uploadUser;

    /** 版本变更说明。 */
    @Column(length = 500)
    private String changelog;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDocUrl() {
        return docUrl;
    }

    public void setDocUrl(String docUrl) {
        this.docUrl = docUrl;
    }

    public String getConvertedDocUrl() {
        return convertedDocUrl;
    }

    public void setConvertedDocUrl(String convertedDocUrl) {
        this.convertedDocUrl = convertedDocUrl;
    }

    public String getConvertedContent() {
        return convertedContent;
    }

    public void setConvertedContent(String convertedContent) {
        this.convertedContent = convertedContent;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public void setStatus(DocumentStatus status) {
        this.status = status;
    }

    public String getUploadUser() {
        return uploadUser;
    }

    public void setUploadUser(String uploadUser) {
        this.uploadUser = uploadUser;
    }

    public String getChangelog() {
        return changelog;
    }

    public void setChangelog(String changelog) {
        this.changelog = changelog;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
