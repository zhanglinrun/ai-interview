package com.linrun.interview.modules.knowledgebase.model;

import com.linrun.interview.modules.knowledgebase.constant.SegmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 知识库分段实体（对齐 know-engine KnowledgeSegment）。
 *
 * <p>每个版本切块后产生若干 segment，文本与元数据落本表，向量化后回写 {@link #embeddingId}
 * 与 {@link SegmentStatus#VECTOR_STORED}。ES 向量删除可按 metadata 中的 docId/version 过滤，
 * 也可按 embeddingId 单条删。
 */
@Entity
@Table(name = "knowledge_base_segment", indexes = {
    @Index(name = "idx_kbs_doc_version", columnList = "documentId,documentVersion"),
    @Index(name = "idx_kbs_status", columnList = "status"),
    @Index(name = "idx_kbs_chunk_id", columnList = "chunkId"),
    @Index(name = "idx_kbs_brother", columnList = "brotherChunkId")
})
public class KnowledgeBaseSegmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 分段文本内容。 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String text;

    /** 业务 chunk ID（来自 splitter metadata，非 ES docId）。 */
    @Column(length = 64)
    private String chunkId;

    /** 父块 chunk ID（父子切片：子 chunk 指向所属更高级标题 chunk，供 small-to-big 上下文扩展）。 */
    @Column(length = 64)
    private String parentChunkId;

    /** 兄弟块组 ID（兄弟切片：同组子 chunk 共享，按 brotherChunkIndex 顺序拼接成完整段落）。 */
    @Column(length = 64)
    private String brotherChunkId;

    /** 兄弟块组内序号（从 1 开始，按序拼接）。 */
    private Integer brotherChunkIndex;

    /** 元数据 JSON（docId/version/fileName/headerLevel/parentChunkId 等）。 */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    /** 所属知识库 ID。 */
    @Column(nullable = false)
    private Long documentId;

    /** 所属版本 ID（指向 knowledge_base_version.version_id）。 */
    @Column(nullable = false)
    private Long documentVersion;

    /** 分段顺序。 */
    private Integer chunkOrder;

    /** ES 返回的向量 ID。 */
    @Column(length = 128)
    private String embeddingId;

    /** 分段状态机。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SegmentStatus status;

    /** 是否跳过向量化（1=跳过，如纯标题块）。 */
    private Integer skipEmbedding = 0;

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
