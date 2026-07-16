package com.linrun.interview.modules.jobinterview.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("job_interview_preparation_runs")
public class PreparationRunEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String runId;
  private Long userId;
  private Long jobDescriptionId;
  private Long resumeId;
  private Long githubRepositoryId;
  private String knowledgeBaseIdsJson;
  private Boolean includePersonalMaterials;
  private JobCodingLanguage codingLanguage;
  private String fingerprint;
  private PreparationStatus status;
  private Integer attempt;
  private String inputSnapshotJson;
  private String planJson;
  private String evidenceSnapshotIdsJson;
  private String dependencyStatusJson;
  private String degradedReasonsJson;
  private String sessionId;
  private String failureCode;
  private String failureDetail;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private LocalDateTime completedAt;
}
