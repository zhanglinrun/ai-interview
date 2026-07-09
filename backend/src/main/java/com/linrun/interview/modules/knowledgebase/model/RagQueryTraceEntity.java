package com.linrun.interview.modules.knowledgebase.model;

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
@TableName("rag_query_traces")
public class RagQueryTraceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String traceId;

    private String question;

    private String rewrittenQuestion;

    private String routeStrategy;

    private String routeReasoning;

    /** Query Decomposition 子查询列表 JSON（P2） */
    private String decomposedQueriesJson;

    /** CRAG 打分：correct / ambiguous / incorrect（P2） */
    private String cragGrade;

    /** CRAG 纠正动作：none / rewrite_retry / fallback_no_evidence（P2） */
    private String cragAction;

    /** 是否尝试了 Neo4j 图谱（Text2Cypher）检索 */
    private Boolean graphAttempted;

    /** 图谱是否命中（false=为空/异常已降级向量检索） */
    private Boolean graphHit;

    /** 图谱命中时的 Cypher 结果片段 */
    private String graphResult;

    private String knowledgeBaseIdsJson;

    private String retrievedJson;

    private String rerankedJson;

    private String finalSourcesJson;

    private String answer;

    private Double confidence;

    private String invalidCitationsJson;

    private Long latencyMs;

    private LocalDateTime createdAt;

}
