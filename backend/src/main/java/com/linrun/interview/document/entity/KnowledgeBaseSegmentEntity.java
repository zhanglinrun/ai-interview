package com.linrun.interview.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import com.linrun.interview.infra.persistence.BaseEntity;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.document.constant.SegmentStatus;

import java.time.LocalDateTime;

/**
 * 知识库分段实体（对齐业界实践 KnowledgeSegment）。
 *
 * <p>每个版本切块后产生若干 segment，文本与元数据落本表，向量化后回写 {@link #embeddingId}
 * 与 {@link SegmentStatus#VECTOR_STORED}。ES 向量删除可按 metadata 中的 docId/version 过滤，
 * 也可按 embeddingId 单条删。
 */
@TableName("document_segments")
public class KnowledgeBaseSegmentEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分段文本内容。 */
    private String text;

    /** 业务 chunk ID（来自 splitter metadata，非 ES docId）。 */
    private String chunkId;

    /** 父块 chunk ID（父子切片：子 chunk 指向所属更高级标题 chunk，供 small-to-big 上下文扩展）。 */
    private String parentChunkId;

    /** 兄弟块组 ID（兄弟切片：同组子 chunk 共享，按 brotherChunkIndex 顺序拼接成完整段落）。 */
    private String brotherChunkId;

    /** 兄弟块组内序号（从 1 开始，按序拼接）。 */
    private Integer brotherChunkIndex;

    /** 元数据 JSON（docId/version/fileName/headerLevel/parentChunkId 等）。 */
    private String metadata;

    /** 数据所有者，作为 MySQL 与 ES 双重隔离字段。 */
    private Long userId;

    /** 证据业务域。 */
    private DataDomain dataDomain;

    /** 域内资源 ID。 */
    private String resourceId;

    /** 不可变资源版本。 */
    private String resourceVersion;

    /** 跨 Trace / 报告稳定的证据 ID。 */
    private String evidenceId;

    /** 当前片段正文 SHA-256。 */
    private String contentHash;

    /** 来源类型。 */
    private String sourceType;

    /** 来源内定位。 */
    private String sourceLocator;

    /** 所属知识库 ID。 */
    private Long documentId;

    /** 所属版本 ID（指向 document_versions.version_id）。 */
    private Long documentVersion;

    /** 分段顺序。 */
    private Integer chunkOrder;

    /** ES 返回的向量 ID。 */
    private String embeddingId;

    /** 分段状态机。 */
    private SegmentStatus status;

    /** 是否跳过向量化（1=跳过，如纯标题块）。 */
    private Integer skipEmbedding = 0;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;



    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public String getParentChunkId() {
        return parentChunkId;
    }

    public void setParentChunkId(String parentChunkId) {
        this.parentChunkId = parentChunkId;
    }

    public String getBrotherChunkId() {
        return brotherChunkId;
    }

    public void setBrotherChunkId(String brotherChunkId) {
        this.brotherChunkId = brotherChunkId;
    }

    public Integer getBrotherChunkIndex() {
        return brotherChunkIndex;
    }

    public void setBrotherChunkIndex(Integer brotherChunkIndex) {
        this.brotherChunkIndex = brotherChunkIndex;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public DataDomain getDataDomain() {
        return dataDomain;
    }

    public void setDataDomain(DataDomain dataDomain) {
        this.dataDomain = dataDomain;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getResourceVersion() {
        return resourceVersion;
    }

    public void setResourceVersion(String resourceVersion) {
        this.resourceVersion = resourceVersion;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public void setEvidenceId(String evidenceId) {
        this.evidenceId = evidenceId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceLocator() {
        return sourceLocator;
    }

    public void setSourceLocator(String sourceLocator) {
        this.sourceLocator = sourceLocator;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Long getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(Long documentVersion) {
        this.documentVersion = documentVersion;
    }

    public Integer getChunkOrder() {
        return chunkOrder;
    }

    public void setChunkOrder(Integer chunkOrder) {
        this.chunkOrder = chunkOrder;
    }

    public String getEmbeddingId() {
        return embeddingId;
    }

    public void setEmbeddingId(String embeddingId) {
        this.embeddingId = embeddingId;
    }

    public SegmentStatus getStatus() {
        return status;
    }

    public void setStatus(SegmentStatus status) {
        this.status = status;
    }

    public Integer getSkipEmbedding() {
        return skipEmbedding;
    }

    public void setSkipEmbedding(Integer skipEmbedding) {
        this.skipEmbedding = skipEmbedding;
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
