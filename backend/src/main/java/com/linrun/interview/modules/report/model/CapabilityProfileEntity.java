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
@TableName("capability_profiles")
public class CapabilityProfileEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private String capabilityAtomId;
  private CapabilityState state;
  private Boolean reviewRequired;
  private Integer evidenceCount;
  private String recentEvidenceIdsJson;
  private LocalDateTime lastEvidenceAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
