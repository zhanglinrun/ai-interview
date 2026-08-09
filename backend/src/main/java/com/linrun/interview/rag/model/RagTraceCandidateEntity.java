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
@TableName("rag_retrieval_candidates")
public class RagTraceCandidateEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ragRunId;
    private String stage;
    private Integer rankNo;
    private String sourceType;
    private String documentId;
    private String segmentId;
    private String evidenceId;
    private Double score;
    private Double rerankScore;
    private String snippet;
    private String metadataJson;
    private Boolean permissionAllowed;
    private Boolean versionMatched;
    private String filterReason;
    private LocalDateTime createdAt;
}
