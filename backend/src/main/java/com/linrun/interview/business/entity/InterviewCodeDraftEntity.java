package com.linrun.interview.business.entity;

import com.linrun.interview.business.constant.JobCodingLanguage;
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
@TableName("interview_code_drafts")
public class InterviewCodeDraftEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private Long sessionId;
  private Long questionId;
  private JobCodingLanguage language;
  private String sourceCode;
  private String sourceHash;
  private String judgeStatus;
  private String judgeSubmissionId;
  private String judgeResultJson;
  private String commandId;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private LocalDateTime submittedAt;
}
