package com.linrun.interview.modules.knowledgebase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;
import com.linrun.interview.modules.knowledgebase.model.RagEvaluationRunEntity;
import com.linrun.interview.modules.knowledgebase.model.RagEvalRequest;
import com.linrun.interview.modules.knowledgebase.model.RagEvalResponse;
import com.linrun.interview.modules.knowledgebase.mapper.RagEvaluationRunMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RAG 检索评测服务测试")
class RagEvaluationServiceTest {

    @Test
    @DisplayName("应计算 Hit@K、MRR 和 NDCG")
    void evaluateMetrics() {
        KnowledgeBaseQueryService queryService = mock(KnowledgeBaseQueryService.class);
        when(queryService.retrieveForEvaluation(any(), any())).thenReturn(List.of(
            segment("c1", "无关内容", 0.91234d),
            segment("c2", "这里解释 JVM 垃圾回收", 0.81234d)
        ));
        RagEvaluationRunMapper runMapper = mock(RagEvaluationRunMapper.class);
        when(runMapper.insert(any(RagEvaluationRunEntity.class))).thenReturn(1);
        RagEvaluationService service = new RagEvaluationService(queryService, runMapper, new ObjectMapper());

        UserContext.setUserId(7L);
        RagEvalResponse response;
        try {
            response = service.evaluate(new RagEvalRequest(
                List.of(1L),
                List.of(new RagEvalRequest.Item("GC 是什么", List.of("垃圾回收"), List.of())),
                2,
                "Java 题库评测"
            ));
        } finally {
            UserContext.clear();
        }

        assertThat(response.runId()).startsWith("rag-eval-");
        assertThat(response.hitRate()).isEqualTo(1.0);
        assertThat(response.mrr()).isEqualTo(0.5);
        assertThat(response.items().getFirst().firstHitRank()).isEqualTo(2);
        assertThat(response.items().getFirst().ndcg()).isGreaterThan(0);
        assertThat(response.items().getFirst().retrievedSegments().getFirst().score())
            .isEqualTo(0.9123d);
        verify(runMapper).insert(any(RagEvaluationRunEntity.class));
    }

    private TextSegment segment(String chunkId, String text, double score) {
        Metadata metadata = Metadata.from(MetadataKeyConstant.CHUNK_ID, chunkId)
            .put("SCORE", score);
        return new TextSegment(text, metadata);
    }
}
