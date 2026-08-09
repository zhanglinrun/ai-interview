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
@TableName("rag_answer_snapshots")
public class RagAnswerSnapshotEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ragRunId;
    private String answer;
    private String groundedStatus;
    private Double confidence;
    private String invalidCitationsJson;
    private Integer tokenCount;
    private LocalDateTime createdAt;
}
