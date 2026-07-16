package com.linrun.interview.modules.capability.model;

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
@TableName("question_templates")
public class QuestionTemplateEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String questionCode;
  private String version;
  private CatalogStatus status;
  private Long atomDefinitionId;
  private String difficulty;
  private String stage;
  private String promptSkeleton;
  private String rubricCode;
  private String rubricVersion;
  private String contentHash;
  private LocalDateTime createdAt;
}
