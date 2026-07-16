package com.linrun.interview.modules.jobinterview.model;

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
@TableName("interview_session_events")
public class InterviewSessionEventEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private String sessionId;
  private String eventType;
  private Long sessionVersion;
  private String payloadJson;
  private LocalDateTime createdAt;
}
