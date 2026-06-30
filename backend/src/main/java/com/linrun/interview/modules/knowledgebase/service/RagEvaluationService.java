package com.linrun.interview.modules.knowledgebase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;
import com.linrun.interview.modules.knowledgebase.model.RagEvaluationRunEntity;
import com.linrun.interview.modules.knowledgebase.model.RagEvalRequest;
import com.linrun.interview.modules.knowledgebase.model.RagEvalResponse;
import com.linrun.interview.modules.knowledgebase.repository.RagEvaluationRunRepository;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RagEvaluationService {

    private final KnowledgeBaseQueryService queryService;
    private final RagEvaluationRunRepository runRepository;
    private final ObjectMapper objectMapper;

    public RagEvalResponse evaluate(RagEvalRequest request) {
        int k = request.k() != null && request.k() > 0 ? request.k() : 5;
        List<RagEvalResponse.ItemResult> results = new ArrayList<>();
        for (RagEvalRequest.Item item : request.items()) {
            List<TextSegment> retrieved = queryService.retrieveForEvaluation(
                request.knowledgeBaseIds(), item.question()).stream().limit(k).toList();
            results.add(evaluateItem(item, retrieved, k));
        }
        int total = results.size();
        double hitRate = total == 0 ? 0 : results.stream().filter(RagEvalResponse.ItemResult::hit).count() * 1.0 / total;
        double mrr = total == 0 ? 0 : results.stream().mapToDouble(RagEvalResponse.ItemResult::reciprocalRank).average().orElse(0);
        double ndcg = total == 0 ? 0 : results.stream().mapToDouble(RagEvalResponse.ItemResult::ndcg).average().orElse(0);
        RagEvalResponse response = new RagEvalResponse(null, total, k, round(hitRate), round(mrr),
            round(ndcg), results);
        String runId = saveRun(request, response);
        return new RagEvalResponse(runId, response.total(), response.k(), response.hitRate(),
            response.mrr(), response.ndcg(), response.items());
    }

    private String saveRun(RagEvalRequest request, RagEvalResponse response) {
        String runId = "rag-eval-" + UUID.randomUUID();
        try {
            int hitCount = (int) response.items().stream().filter(RagEvalResponse.ItemResult::hit).count();
            runRepository.save(RagEvaluationRunEntity.builder()
                .userId(UserContext.requireUserId())
                .runId(runId)
                .title(titleOrDefault(request.title()))
                .knowledgeBaseIdsJson(objectMapper.writeValueAsString(request.knowledgeBaseIds()))
                .casesJson(objectMapper.writeValueAsString(Map.of(
                    "requestItems", request.items(),
                    "results", response.items(),
                    "ndcg", response.ndcg()
                )))
                .totalCases(response.total())
                .hitCount(hitCount)
                .hitRate(response.hitRate())
                .meanReciprocalRank(response.mrr())
                .topk(response.k())
                .build());
            return runId;
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "保存 RAG 评测结果失败", e);
        }
    }

    private String titleOrDefault(String title) {
        if (title == null || title.isBlank()) {
            return "RAG 检索评测";
        }
        return title.length() <= 120 ? title : title.substring(0, 120);
    }

    private RagEvalResponse.ItemResult evaluateItem(RagEvalRequest.Item item, List<TextSegment> retrieved, int k) {
        Set<String> expectedChunkIds = item.expectedChunkIds() == null
            ? Set.of()
            : new HashSet<>(item.expectedChunkIds());
        List<String> expectedKeywords = item.expectedKeywords() == null
            ? List.of()
            : item.expectedKeywords();

        int firstHitRank = 0;
        List<String> chunkIds = new ArrayList<>();
        double dcg = 0.0;
        for (int i = 0; i < retrieved.size(); i++) {
            TextSegment segment = retrieved.get(i);
            String chunkId = segment.metadata().getString(MetadataKeyConstant.CHUNK_ID);
            chunkIds.add(chunkId);
            if (matches(segment, expectedChunkIds, expectedKeywords)) {
                int rank = i + 1;
                if (firstHitRank == 0) {
                    firstHitRank = rank;
                }
                dcg += 1.0 / log2(rank + 1);
            }
        }
        int idealHits = Math.min(k, Math.max(1, expectedChunkIds.size() + expectedKeywords.size()));
        double idcg = 0.0;
        for (int i = 1; i <= idealHits; i++) {
            idcg += 1.0 / log2(i + 1);
        }
        double reciprocalRank = firstHitRank == 0 ? 0 : 1.0 / firstHitRank;
        double ndcg = idcg == 0 ? 0 : dcg / idcg;
        return new RagEvalResponse.ItemResult(
            item.question(), firstHitRank > 0, firstHitRank, round(reciprocalRank), round(ndcg), chunkIds);
    }

    private boolean matches(TextSegment segment, Set<String> expectedChunkIds, List<String> expectedKeywords) {
        String chunkId = segment.metadata().getString(MetadataKeyConstant.CHUNK_ID);
        if (chunkId != null && expectedChunkIds.contains(chunkId)) {
            return true;
        }
        String text = segment.text().toLowerCase(Locale.ROOT);
        return expectedKeywords.stream()
            .filter(keyword -> keyword != null && !keyword.isBlank())
            .map(keyword -> keyword.toLowerCase(Locale.ROOT))
            .anyMatch(text::contains);
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
