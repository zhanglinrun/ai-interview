package com.linrun.interview.rag.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("rag_runs")
public class RagTraceRunEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ragRunId;
    private String traceId;
    private String agentRunId;
    private String rootSpanId;
    private Long userId;
    private String sessionId;
    private String question;
    private String status;
    private String routeSource;
    private String routeIntent;
    private Long latencyMs;
    private String degradedReason;
    private String answerSummary;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
