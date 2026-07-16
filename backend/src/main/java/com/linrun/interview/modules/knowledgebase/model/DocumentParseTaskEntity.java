package com.linrun.interview.modules.knowledgebase.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linrun.interview.common.mybatis.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("document_parse_tasks")
public class DocumentParseTaskEntity extends BaseEntity {

  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private Long documentId;
  private Long versionId;
  private String provider;
  private String providerTaskId;
  private DocumentParseTaskStatus status;
  private Integer attempt;
  private LocalDateTime nextPollAt;
  private String failureCode;
  private String failureDetail;
  private Boolean fallbackUsed;
  private String fallbackReason;
  private String storageKey;
  private String fileName;
  private String contentType;
  private LocalDateTime startedAt;
  private LocalDateTime completedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
