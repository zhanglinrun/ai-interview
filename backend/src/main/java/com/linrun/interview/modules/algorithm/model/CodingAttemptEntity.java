package com.linrun.interview.modules.algorithm.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("coding_attempts")
public class CodingAttemptEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String attemptId;
  private Long userId;
  private Long problemVersionId;
  private CodingAttemptMode mode;
  private String contextId;
  private CodingLanguage language;
  private CodingAttemptStatus status;
  private LocalDateTime startedAt;
  private LocalDateTime submittedAt;
  private LocalDateTime completedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  @Version
  private Integer lockVersion;
}
