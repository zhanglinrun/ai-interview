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
@Table(name = "rag_query_traces", indexes = {
    @Index(name = "idx_rag_query_traces_user_created", columnList = "userId,createdAt")
})
public class RagQueryTraceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 80, unique = true)
    private String traceId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String rewrittenQuestion;

    @Column(length = 40)
    private String routeStrategy;

    @Column(length = 500)
    private String routeReasoning;

    @Column(columnDefinition = "TEXT")
    private String knowledgeBaseIdsJson;

    @Column(columnDefinition = "TEXT")
    private String retrievedJson;

    @Column(columnDefinition = "TEXT")
    private String finalSourcesJson;

    @Column(columnDefinition = "TEXT")
    private String answer;

    private Double confidence;

    @Column(columnDefinition = "TEXT")
    private String invalidCitationsJson;

    private Long latencyMs;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
