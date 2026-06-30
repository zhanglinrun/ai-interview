package com.linrun.interview.modules.knowledgebase.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rag_evaluation_runs", indexes = {
    @Index(name = "idx_rag_eval_created", columnList = "createdAt"),
    @Index(name = "idx_rag_eval_hit_rate_created", columnList = "hitRate,createdAt"),
    @Index(name = "idx_rag_evaluation_runs_user_id", columnList = "userId")
})
public class RagEvaluationRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 80, unique = true)
    private String runId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String casesJson;

    @Column(columnDefinition = "TEXT")
    private String knowledgeBaseIdsJson;

    private Integer totalCases;

    private Integer hitCount;

    private Double hitRate;

    private Double meanReciprocalRank;

    private Double minScore;

    private Integer topk;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
