package com.linrun.interview.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import com.linrun.interview.infra.persistence.BaseEntity;
import com.linrun.interview.document.constant.DocumentStatus;

import java.time.LocalDateTime;

/**
 * 知识库文档版本实体（对齐业界实践 KnowledgeDocumentVersion）。
 *
 * <p>与 {@link KnowledgeBaseEntity} 一对多：一个知识库可有多个版本，每个版本独立存储原始文件 URL、
 * 转换后 Markdown 文本内容、内容哈希与状态。{@link KnowledgeBaseEntity#getCurrentVersionId()} 指向当前激活版本。
 */
@TableName("document_versions")
public class KnowledgeBaseVersionEntity extends BaseEntity {

    @TableId(value = "version_id", type = IdType.AUTO)
    private Long versionId;

    /** 关联知识库 ID（documents.id）。 */
    private Long docId;

    /** 语义化版本号，如 1.0.0。 */
    private String version;

    /** 原始文件 URL（MinIO）。 */
    private String docUrl;

    /** 原始文件对象存储键；用于短时预签名与失败补偿，不向前端暴露。 */
    private String storageKey;

    /** 转换后 Markdown 文本内容（历史兼容：早期全文存 LOB；新上传优先写 convertedDocUrl）。 */
    private String convertedContent;

    /** 转换后 Markdown 的 MinIO 对象 URL。 */
    private String convertedDocUrl;

    /** 文档内容 SHA-256（跨版本去重）。 */
    private String contentHash;

    /** 版本状态机。 */
    private DocumentStatus status;

    /** 向量化任务累计尝试次数。 */
    private Integer embeddingAttempt;

    /** 当前向量化任务租约开始时间。 */
    private LocalDateTime embeddingClaimedAt;

    /** 退避后的最早下次重试时间。 */
    private LocalDateTime embeddingNextRetryAt;

    /** 最近一次向量化错误摘要。 */
    private String embeddingLastError;

    /** 是否已达到最大尝试次数，需要人工重新切块或重置。 */
    private Boolean embeddingTerminalFailure;

    /** 上传用户标识。 */
    private String uploadUser;

    /** 版本变更说明。 */
    private String changelog;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;



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

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getConvertedContent() {
        return convertedContent;
    }

    public void setConvertedContent(String convertedContent) {
        this.convertedContent = convertedContent;
    }

    public String getConvertedDocUrl() {
        return convertedDocUrl;
    }

    public void setConvertedDocUrl(String convertedDocUrl) {
        this.convertedDocUrl = convertedDocUrl;
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

    public Integer getEmbeddingAttempt() {
        return embeddingAttempt;
    }

    public void setEmbeddingAttempt(Integer embeddingAttempt) {
        this.embeddingAttempt = embeddingAttempt;
    }

    public LocalDateTime getEmbeddingClaimedAt() {
        return embeddingClaimedAt;
    }

    public void setEmbeddingClaimedAt(LocalDateTime embeddingClaimedAt) {
        this.embeddingClaimedAt = embeddingClaimedAt;
    }

    public LocalDateTime getEmbeddingNextRetryAt() {
        return embeddingNextRetryAt;
    }

    public void setEmbeddingNextRetryAt(LocalDateTime embeddingNextRetryAt) {
        this.embeddingNextRetryAt = embeddingNextRetryAt;
    }

    public String getEmbeddingLastError() {
        return embeddingLastError;
    }

    public void setEmbeddingLastError(String embeddingLastError) {
        this.embeddingLastError = embeddingLastError;
    }

    public Boolean getEmbeddingTerminalFailure() {
        return embeddingTerminalFailure;
    }

    public void setEmbeddingTerminalFailure(Boolean embeddingTerminalFailure) {
        this.embeddingTerminalFailure = embeddingTerminalFailure;
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
