package com.linrun.interview.business.entity;

import com.linrun.interview.business.constant.CatalogStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linrun.interview.rag.model.DataDomain;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("platform_knowledge_manifest")
public class PlatformKnowledgeManifestEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long ownerUserId;
  private DataDomain dataDomain;
  private String evidenceId;
  private String resourceId;
  private String resourceVersion;
  private String title;
  private String summary;
  private String sourceType;
  private String sourceLocator;
  private String contentHash;
  private String capabilityAtomIdsJson;
  private CatalogStatus status;
  private LocalDateTime createdAt;
}
