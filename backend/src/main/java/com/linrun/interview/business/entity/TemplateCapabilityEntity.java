package com.linrun.interview.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("template_capabilities")
public class TemplateCapabilityEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long templateId;
  private Long atomDefinitionId;
  private BigDecimal defaultWeight;
  private Integer minimumCoverage;
  private String questionTypesJson;
}
