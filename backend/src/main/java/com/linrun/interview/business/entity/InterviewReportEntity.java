package com.linrun.interview.business.entity;

import com.linrun.interview.business.constant.ReportStatus;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
@TableName("interview_evidence_reports")
public class InterviewReportEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String reportId;
  private Long userId;
  private Long sessionId;
  private ReportStatus status;
  private String objectiveFactsJson;
  private String summaryJson;
  private String gapsJson;
  private Boolean objectiveReady;
  private Boolean summaryReady;
  private Boolean profileApplied;
  private Integer generationAttempt;
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LocalDateTime generationClaimedAt;
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String failureCode;
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String failureDetail;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private LocalDateTime completedAt;
}
