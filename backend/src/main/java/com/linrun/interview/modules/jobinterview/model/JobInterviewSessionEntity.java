package com.linrun.interview.modules.jobinterview.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 与旧文本面试共享 interview_sessions 事实表，仅映射岗位实战所需字段。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interview_sessions")
public class JobInterviewSessionEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private String sessionId;
  private String skillId;
  private String difficulty;
  private Long resumeId;
  private Integer totalQuestions;
  private Integer currentQuestionIndex;
  private JobInterviewSessionStatus status;
  private String questionsJson;
  private LocalDateTime createdAt;
  private LocalDateTime completedAt;
  private String llmProvider;
  private String knowledgeBaseIdsJson;
  private String interviewPlanJson;
  private String preparationRunId;
  private Long jobDescriptionId;
  private Integer jobDescriptionVersion;
  private String capabilityTemplateCode;
  private String capabilityTemplateVersion;
  private String planVersion;
  private String promptVersion;
  private String evidenceSnapshotId;
  private String evidenceSnapshotIdsJson;
  private Long githubRepositoryId;
  private String githubCommitSha;
  private JobCodingLanguage codingLanguage;
  private Long sessionVersion;
  private JobInterviewStage currentStage;
  private Long currentQuestionId;
  private Boolean personalKnowledgeEnabled;
  private String degradedReasonsJson;
  private String activeCommandId;
  private Integer continuationCount;
  private Integer reflectionCount;
  private LocalDateTime startedAt;
  private LocalDateTime stageStartedAt;
  private LocalDateTime stageDeadlineAt;
  private LocalDateTime softDeadlineAt;
  private LocalDateTime lastActivityAt;
  private LocalDateTime resumeExpiresAt;
  private LocalDateTime pausedAt;
  private LocalDateTime abortedAt;
}
