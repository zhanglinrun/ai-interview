package com.linrun.interview.modules.algorithm.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("judge_submissions")
public class JudgeSubmissionEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String submissionId;
  private Long userId;
  private Long attemptId;
  private String idempotencyKey;
  private TestSuiteType suiteType;
  private CodingLanguage language;
  private String sourceCode;
  private String codeHash;
  private JudgeStatus status;
  private String provider;
  private String providerSubmissionId;
  private Integer passedCount;
  private Integer totalCount;
  private String diagnostic;
  private Long timeMs;
  private Long memoryKb;
  private String failureCode;
  private LocalDateTime submittedAt;
  private LocalDateTime completedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  @Version
  private Integer lockVersion;
}
