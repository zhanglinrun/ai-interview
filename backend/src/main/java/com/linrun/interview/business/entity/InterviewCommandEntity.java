package com.linrun.interview.business.entity;

import com.linrun.interview.business.constant.InterviewCommandStatus;
import com.linrun.interview.business.constant.InterviewCommandType;
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
@TableName("interview_commands")
public class InterviewCommandEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private String sessionId;
  private String commandId;
  private String traceId;
  private String agentRunId;
  private InterviewCommandType commandType;
  private Long expectedSessionVersion;
  private InterviewCommandStatus status;
  private String resultJson;
  private String failureCode;
  private String failureDetail;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private LocalDateTime completedAt;
}
