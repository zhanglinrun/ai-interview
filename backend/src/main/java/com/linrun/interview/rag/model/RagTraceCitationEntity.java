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
@TableName("rag_citations")
public class RagTraceCitationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ragRunId;
    private Integer citationIndex;
    private String evidenceId;
    private String sourceLocator;
    private Boolean cited;
    private Boolean valid;
    private Double confidence;
    private LocalDateTime createdAt;
}
