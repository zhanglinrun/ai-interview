package com.linrun.interview.modules.jobtarget.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
@TableName("job_capability_mappings")
public class JobCapabilityMappingEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private Long jobDescriptionId;
  private String atomId;
  private String atomVersion;
  private String capabilityName;
  private CapabilityMappingSource mappingSource;
  private String evidenceText;
  private Integer evidenceStart;
  private Integer evidenceEnd;
  private BigDecimal suggestedWeight;
  private BigDecimal confirmedWeight;
  private BigDecimal confidence;
  private Boolean enabled;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
