package com.linrun.interview.modules.github.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linrun.interview.common.evidence.DataDomain;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 可引用的代码证据块，行号基于固定 Commit SHA 的原始文件。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("github_code_evidence")
public class GithubCodeEvidenceEntity {

  @TableId(type = IdType.AUTO)
  private Long id;
  private Long ownerUserId;
  private DataDomain dataDomain;
  private String resourceId;
  private String resourceVersion;
  private Long repositoryId;
  private String commitSha;
  private String path;
  private String language;
  private String symbolName;
  private String symbolKind;
  private Integer startLine;
  private Integer endLine;
  private String parentSummary;
  private String content;
  private String contentHash;
  private String evidenceId;
  private String sourceLocator;
  private String embeddingId;
  private LocalDateTime createdAt;
}
