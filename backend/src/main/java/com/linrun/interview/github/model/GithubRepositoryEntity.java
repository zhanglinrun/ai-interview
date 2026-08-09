package com.linrun.interview.github.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户绑定的公共 GitHub 仓库及固定 SHA。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("github_repository_bindings")
public class GithubRepositoryEntity {

  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private String ownerName;
  private String repositoryName;
  private String repositoryUrl;
  private String defaultBranch;
  private String fixedCommitSha;
  private Long sourceSizeKb;
  private GithubRepositorySyncStatus syncStatus;
  private String syncFingerprint;
  private Integer syncedFileCount;
  private Long syncedBytes;
  private String syncError;
  private Boolean sourceAvailable;
  private String coreModulesJson;
  private String responsibilities;
  private String keyDecisions;
  private String problemsSolved;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private LocalDateTime lastSyncedAt;
}
