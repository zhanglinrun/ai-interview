package com.linrun.interview.rag.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
@TableName("rag_evaluation_runs")
public class RagEvaluationRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String runId;

    private String title;

    private String casesJson;

    private String knowledgeBaseIdsJson;

    private Integer totalCases;

    private Integer hitCount;

    private Double hitRate;

    private Double meanReciprocalRank;

    private Double ndcg;

    private Double retrievalRecall;

    private Double retrievalPrecision;

    /** 兼容旧列；与 retrievalRecall 同值。 */
    private Double citationHitRate;

    /** 兼容旧列；与 retrievalPrecision 同值。 */
    private Double citationCoverage;

    private Double minScore;

    private Integer topk;

    private LocalDateTime createdAt;

}
