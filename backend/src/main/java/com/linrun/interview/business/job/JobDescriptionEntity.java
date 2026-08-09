package com.linrun.interview.business.job;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linrun.interview.business.constant.JobTrack;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("job_descriptions")
public class JobDescriptionEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private String targetKey;
  private Integer version;
  private String title;
  private String company;
  private JobTrack jobTrack;
  private String jdText;
  private String sourceUrl;
  private String contentHash;
  private JobDescriptionStatus status;
  private String templateCode;
  private String templateVersion;
  private String rubricVersionsJson;
  private LocalDateTime frozenAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
