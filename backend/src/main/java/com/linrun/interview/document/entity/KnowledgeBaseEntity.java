package com.linrun.interview.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import com.linrun.interview.infra.persistence.BaseEntity;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.constant.KnowledgeBaseType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 知识库实体
 */
@TableName("documents")
public class KnowledgeBaseEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    // 文件内容的SHA-256哈希值，用于去重
    private String fileHash;

    // 知识库名称（用户自定义或从文件名提取）
    private String name;

    // 分类/分组（如"Java面试"、"项目文档"等）
    private String category;

    // 原始文件名
    private String originalFilename;
    
    // 文件大小（字节）
    private Long fileSize;
    
    // 文件类型
    private String contentType;
    
    // MinIO 存储的文件 Key
    private String storageKey;
    
    // MinIO 存储的文件 URL
    private String storageUrl;
    
    // 上传时间
    private LocalDateTime uploadedAt;
    
    // 最后访问时间
    private LocalDateTime lastAccessedAt;
    
    // 访问次数
    private Integer accessCount = 0;
    
    // 问题数量（用户针对此知识库提问的次数）
    private Integer questionCount = 0;

    // 当前激活版本 ID（指向 document_versions.version_id），三表重构后向量状态由版本承载
    private Long currentVersionId;

    // 文档描述（对齐业界实践 KnowledgeDocument.description）
    private String description;

    // 文档状态机（对齐业界实践 DocumentStatus）
    private DocumentStatus docStatus;

    /** 可见范围：PRIVATE / PUBLIC（对齐业界实践 accessibleBy）。 */
    private String accessibleBy;

    /** 到期日；过期后不参与检索。 */
    private LocalDate expireDate;

    /** 知识库类型：DOCUMENT_SEARCH / DATA_QUERY。 */
    private KnowledgeBaseType knowledgeBaseType;

    /** DATA_QUERY 动态表物理表名。 */
    private String tableName;

    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
    
    public String getFileHash() {
        return fileHash;
    }
    
    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getOriginalFilename() {
        return originalFilename;
    }
    
    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }
    
    public Long getFileSize() {
        return fileSize;
    }
    
    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }
    
    public String getContentType() {
        return contentType;
    }
    
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
    
    public String getStorageKey() {
        return storageKey;
    }
    
    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }
    
    public String getStorageUrl() {
        return storageUrl;
    }
    
    public void setStorageUrl(String storageUrl) {
        this.storageUrl = storageUrl;
    }
    
    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }
    
    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
    
    public LocalDateTime getLastAccessedAt() {
        return lastAccessedAt;
    }
    
    public void setLastAccessedAt(LocalDateTime lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }
    
    public Integer getAccessCount() {
        return accessCount;
    }
    
    public void setAccessCount(Integer accessCount) {
        this.accessCount = accessCount;
    }
    
    public Integer getQuestionCount() {
        return questionCount;
    }
    
    public void setQuestionCount(Integer questionCount) {
        this.questionCount = questionCount;
    }
    
    public void incrementAccessCount() {
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }
    
    public void incrementQuestionCount() {
        this.questionCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Long getCurrentVersionId() {
        return currentVersionId;
    }

    public void setCurrentVersionId(Long currentVersionId) {
        this.currentVersionId = currentVersionId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public DocumentStatus getDocStatus() {
        return docStatus;
    }

    public void setDocStatus(DocumentStatus docStatus) {
        this.docStatus = docStatus;
    }

    public String getAccessibleBy() {
        return accessibleBy;
    }

    public void setAccessibleBy(String accessibleBy) {
        this.accessibleBy = accessibleBy;
    }

    public LocalDate getExpireDate() {
        return expireDate;
    }

    public void setExpireDate(LocalDate expireDate) {
        this.expireDate = expireDate;
    }

    public KnowledgeBaseType getKnowledgeBaseType() {
        return knowledgeBaseType;
    }

    public void setKnowledgeBaseType(KnowledgeBaseType knowledgeBaseType) {
        this.knowledgeBaseType = knowledgeBaseType;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
}

