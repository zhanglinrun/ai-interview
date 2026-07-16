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
@TableName("evaluation_rubrics")
public class EvaluationRubricEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String rubricCode;
  private String version;
  private CatalogStatus status;
  private String dimensionsJson;
  private String contentHash;
  private LocalDateTime createdAt;
}
