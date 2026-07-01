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
