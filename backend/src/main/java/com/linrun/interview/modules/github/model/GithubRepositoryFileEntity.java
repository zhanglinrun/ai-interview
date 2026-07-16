package com.linrun.interview.modules.github.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 固定 SHA 下的文件清单；contentSnapshot 只对已通过安全检查的入选文本赋值。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("github_repository_files")
public class GithubRepositoryFileEntity {

  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private Long repositoryId;
  private String commitSha;
  private String path;
  private String blobSha;
  private Long byteSize;
  private String language;
  private GithubFileKind fileKind;
  private GithubFileStatus status;
  private String statusReason;
  private Boolean defaultIncluded;
  private String contentHash;
  private String contentSnapshot;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
