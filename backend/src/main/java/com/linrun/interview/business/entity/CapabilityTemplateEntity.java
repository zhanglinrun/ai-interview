package com.linrun.interview.business.entity;

import com.linrun.interview.business.constant.CatalogStatus;
import com.linrun.interview.business.constant.JobTrack;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("capability_templates")
public class CapabilityTemplateEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String templateCode;
  private JobTrack jobTrack;
  private String version;
  private CatalogStatus status;
  private String sourceName;
  private String sourceLocator;
  private String contentHash;
  private LocalDate effectiveDate;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
