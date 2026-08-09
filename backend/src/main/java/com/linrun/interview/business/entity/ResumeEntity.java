package com.linrun.interview.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linrun.interview.common.model.AsyncTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 简历实体
 * Resume Entity for deduplication and persistence
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("resumes")
public class ResumeEntity {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long userId;

  private String fileHash;

  private String originalFilename;

  private Long fileSize;

  private String contentType;

  private String storageKey;

  private String storageUrl;

  private String resumeText;

  private LocalDateTime uploadedAt;

  private LocalDateTime lastAccessedAt;

  @Builder.Default
  private Integer accessCount = 0;

  @Builder.Default
  private AsyncTaskStatus analyzeStatus = AsyncTaskStatus.PENDING;

  private String analyzeError;

  public void incrementAccessCount() {
    this.accessCount++;
    this.lastAccessedAt = LocalDateTime.now();
  }
}
