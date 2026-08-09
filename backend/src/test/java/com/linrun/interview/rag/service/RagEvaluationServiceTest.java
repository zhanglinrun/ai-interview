package com.linrun.interview.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.rag.constant.MetadataKeyConstant;
import com.linrun.interview.rag.model.RagEvaluationRunEntity;
import com.linrun.interview.rag.model.RagEvalRequest;
import com.linrun.interview.rag.model.RagEvalResponse;
import com.linrun.interview.rag.mapper.RagEvaluationRunMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    @DisplayName("重复命中同一关键词时只奖励首次新增证据")
    void repeatedKeywordMatchesOnlyRewardFirstEvidence() {
        KnowledgeBaseQueryService queryService = mock(KnowledgeBaseQueryService.class);
        when(queryService.retrieveForEvaluation(any(), any())).thenReturn(List.of(
            segment("c1", "无关内容", 0.95d),
            segment("c2", "Elasticsearch 用于混合检索", 0.90d),
            segment("c3", "Elasticsearch 保存向量和元数据", 0.85d),
            segment("c4", "Elasticsearch 支持 BM25", 0.80d)
        ));
        RagEvaluationRunMapper runMapper = mock(RagEvaluationRunMapper.class);
        when(runMapper.insert(any(RagEvaluationRunEntity.class))).thenReturn(1);
        RagEvaluationService service = new RagEvaluationService(
            queryService, runMapper, new ObjectMapper());

        UserContext.setUserId(7L);
        RagEvalResponse response;
        try {
            response = service.evaluate(new RagEvalRequest(
                List.of(1L),
                List.of(new RagEvalRequest.Item(
                    "系统使用什么检索引擎", List.of("Elasticsearch"), List.of())),
                4,
                "重复关键词评测"
            ));
        } finally {
            UserContext.clear();
        }

        RagEvalResponse.ItemResult item = response.items().getFirst();
        assertThat(item.ndcg()).isEqualTo(0.6309);
        assertThat(item.ndcg()).isLessThan(1.0);
        assertThat(item.retrievalRecall()).isEqualTo(1.0);
        assertThat(item.retrievalPrecision()).isEqualTo(0.25);
        assertThat(item.citationHitRate()).isEqualTo(item.retrievalRecall());
        assertThat(item.citationCoverage()).isEqualTo(item.retrievalPrecision());
    }

    @Test
    @DisplayName("漏召回期望证据时 NDCG 应小于一")
    void missingExpectedEvidenceReducesNdcg() {
        KnowledgeBaseQueryService queryService = mock(KnowledgeBaseQueryService.class);
        when(queryService.retrieveForEvaluation(any(), any())).thenReturn(List.of(
            segment("c1", "Redis 提供缓存", 0.95d),
            segment("c2", "无关内容", 0.90d)
        ));
        RagEvaluationRunMapper runMapper = mock(RagEvaluationRunMapper.class);
        when(runMapper.insert(any(RagEvaluationRunEntity.class))).thenReturn(1);
        RagEvaluationService service = new RagEvaluationService(
            queryService, runMapper, new ObjectMapper());

        RagEvalResponse response = evaluate(service, new RagEvalRequest.Item(
            "缓存和搜索分别使用什么组件",
            List.of(" Redis ", "redis", "Elasticsearch"),
            List.of()));

        assertThat(response.ndcg()).isEqualTo(0.6131);
        assertThat(response.ndcg()).isLessThan(1.0);
        assertThat(response.retrievalRecall()).isEqualTo(0.5);
        assertThat(response.retrievalPrecision()).isEqualTo(0.5);

        ArgumentCaptor<RagEvaluationRunEntity> captor =
            ArgumentCaptor.forClass(RagEvaluationRunEntity.class);
        verify(runMapper).insert(captor.capture());
        assertThat(captor.getValue().getRetrievalRecall()).isEqualTo(0.5);
        assertThat(captor.getValue().getRetrievalPrecision()).isEqualTo(0.5);
        assertThat(captor.getValue().getCitationHitRate()).isEqualTo(0.5);
        assertThat(captor.getValue().getCitationCoverage()).isEqualTo(0.5);
        assertThat(captor.getValue().getCasesJson())
            .contains("retrievalRecall", "retrievalPrecision");
    }

    @Test
    @DisplayName("重复 chunk 不应重复贡献精确率或 DCG")
    void duplicateChunkIsCountedOnce() {
        KnowledgeBaseQueryService queryService = mock(KnowledgeBaseQueryService.class);
        when(queryService.retrieveForEvaluation(any(), any())).thenReturn(List.of(
            segment("c1", "Redis", 0.95d),
            segment("c1", "Redis 重复结果", 0.90d),
            segment("c2", "无关内容", 0.85d)
        ));
        RagEvaluationService service = new RagEvaluationService(
            queryService, successfulRunMapper(), new ObjectMapper());

        RagEvalResponse response = evaluate(service, new RagEvalRequest.Item(
            "缓存组件", List.of("redis"), List.of()));

        assertThat(response.ndcg()).isEqualTo(1.0);
        assertThat(response.retrievalPrecision()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("没有期望证据的评测项应被拒绝")
    void itemWithoutExpectedEvidenceIsRejected() {
        KnowledgeBaseQueryService queryService = mock(KnowledgeBaseQueryService.class);
        when(queryService.retrieveForEvaluation(any(), any())).thenReturn(List.of());
        RagEvaluationService service = new RagEvaluationService(
            queryService, successfulRunMapper(), new ObjectMapper());

        UserContext.setUserId(7L);
        try {
            assertThatThrownBy(() -> service.evaluate(new RagEvalRequest(
                List.of(1L),
                List.of(new RagEvalRequest.Item("无标准答案", List.of(" "), List.of())),
                4,
                "非法评测"
            ))).isInstanceOf(com.linrun.interview.common.exception.BusinessException.class);
        } finally {
            UserContext.clear();
        }
        verifyNoInteractions(queryService);
    }

    private RagEvalResponse evaluate(
        RagEvaluationService service, RagEvalRequest.Item item) {
        UserContext.setUserId(7L);
        try {
            return service.evaluate(new RagEvalRequest(
                List.of(1L), List.of(item), 4, "指标评测"));
        } finally {
            UserContext.clear();
        }
    }

    private RagEvaluationRunMapper successfulRunMapper() {
        RagEvaluationRunMapper runMapper = mock(RagEvaluationRunMapper.class);
        when(runMapper.insert(any(RagEvaluationRunEntity.class))).thenReturn(1);
        return runMapper;
    }

    private TextSegment segment(String chunkId, String text, double score) {
        Metadata metadata = Metadata.from(MetadataKeyConstant.CHUNK_ID, chunkId)
            .put("SCORE", score);
        return new TextSegment(text, metadata);
    }
}
