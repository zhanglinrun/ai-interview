package com.linrun.interview.business.entity;

import com.linrun.interview.business.constant.JobInterviewStage;
import com.linrun.interview.business.constant.QuestionStatus;
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
@TableName("interview_questions")
public class JobInterviewQuestionEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private Long sessionId;
  private Integer questionIndex;
  private Integer sortOrder;
  private JobInterviewStage stage;
  private String questionType;
  private String questionText;
  private String capabilityAtomId;
  private String capabilityAtomVersion;
  private String questionTemplateCode;
  private String questionTemplateVersion;
  private String rubricCode;
  private String rubricVersion;
  private String evidenceSnapshotId;
  private String evidenceIdsJson;
  private Integer budgetSeconds;
  private Long parentQuestionId;
  private Boolean followUp;
  private Integer reflectionRounds;
  private String promptVersion;
  private String modelSnapshot;
  private QuestionStatus status;
  private LocalDateTime createdAt;
  private LocalDateTime answeredAt;
}
