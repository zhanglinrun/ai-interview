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
@TableName("capability_content_imports")
public class CapabilityContentImportEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String schemaVersion;
  private String contentVersion;
  private String sourceName;
  private String sourceLocator;
  private String checksum;
  private String status;
  private LocalDateTime importedAt;
}
