package com.linrun.interview.business.entity;

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
@TableName("algorithm_content_imports")
public class AlgorithmContentImportEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String schemaVersion;
  private String contentVersion;
  private String checksum;
  private Integer problemCount;
  private Integer enabledCount;
  private LocalDateTime importedAt;
}
