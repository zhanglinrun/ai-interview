package com.linrun.interview.modules.jobinterview.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linrun.interview.common.evidence.EvidenceStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interview_answers")
public class JobInterviewAnswerEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private Long sessionId;
  private Integer questionIndex;
  private Long questionId;
  private String question;
  private String category;
  private String userAnswer;
  private Integer score;
  private String feedback;
  private String commandId;
  private AnswerAssessmentStatus assessmentStatus;
  private String assessmentJson;
  private BigDecimal assessmentConfidence;
  private RecommendedAction recommendedAction;
  private EvidenceStatus evidenceStatus;
  private String objectiveEvidenceIdsJson;
  private String promptVersion;
  private String modelSnapshot;
  private Long latencyMs;
  private Integer inputTokens;
  private Integer outputTokens;
  private Integer retryCount;
  private String degradedReason;
  private LocalDateTime answeredAt;
}
