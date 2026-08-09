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
@TableName("rag_stage_runs")
public class RagTraceStageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ragRunId;
    private String stage;
    private String status;
    private String dataSource;
    private String inputSummary;
    private String outputSummary;
    private String metadataJson;
    /** 实际使用的模型供应商和模型名；未经过模型的阶段保持为空。 */
    private String provider;
    private String modelName;
    private Integer inputTokens;
    private Integer outputTokens;
    /** 权限、租户和版本过滤条件的脱敏快照。 */
    private String filterJson;
    /** fallback、超时或降级原因，正常阶段为空。 */
    private String fallbackReason;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long latencyMs;
    private String errorMessage;
}
