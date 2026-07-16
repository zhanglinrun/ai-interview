package com.linrun.interview.modules.report.model;

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
@TableName("training_tasks")
public class TrainingTaskEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String taskId;
  private Long userId;
  private String reportId;
  private String capabilityAtomId;
  private TrainingType trainingType;
  private TrainingStatus status;
  private Long sourceQuestionId;
  private String questionText;
  private String questionVersion;
  private String evidenceScopeJson;
  private Boolean hintUsed;
  private Boolean answerViewed;
  private Integer redoCount;
  private Integer resultScore;
  private LocalDateTime createdAt;
  private LocalDateTime startedAt;
  private LocalDateTime completedAt;
  private LocalDateTime updatedAt;
}
