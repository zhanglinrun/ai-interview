package com.linrun.interview.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.rag.constant.MetadataKeyConstant;
import com.linrun.interview.rag.model.RagEvaluationRunEntity;
import com.linrun.interview.rag.model.RagEvalRequest;
import com.linrun.interview.rag.model.RagEvalResponse;
import com.linrun.interview.rag.mapper.RagEvaluationRunMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
public class RagEvaluationService
    extends ServiceImpl<RagEvaluationRunMapper, RagEvaluationRunEntity> {

    private final KnowledgeBaseQueryService queryService;
    private final RagEvaluationRunMapper runRepository;
    private final ObjectMapper objectMapper;

    public RagEvaluationService(
        KnowledgeBaseQueryService queryService,
        RagEvaluationRunMapper runRepository,
        ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.baseMapper = runRepository;
    }

    public RagEvalResponse evaluate(RagEvalRequest request) {
        int k = request.k() != null && request.k() > 0 ? request.k() : 5;
        List<RagEvalResponse.ItemResult> results = new ArrayList<>();
        for (RagEvalRequest.Item item : request.items()) {
            validateGroundTruth(item);
            List<TextSegment> retrieved = queryService.retrieveForEvaluation(
                request.knowledgeBaseIds(), item.question()).stream().limit(k).toList();
            results.add(evaluateItem(item, retrieved, k));
        }
        int total = results.size();
        // hitRate = 按题 0/1，简历 Context Precision 落地这一列，不是 ragas 库的逐块精度。
        double hitRate = total == 0 ? 0 : results.stream().filter(RagEvalResponse.ItemResult::hit).count() * 1.0 / total;
        double mrr = total == 0 ? 0 : results.stream().mapToDouble(RagEvalResponse.ItemResult::reciprocalRank).average().orElse(0);
        double ndcg = total == 0 ? 0 : results.stream().mapToDouble(RagEvalResponse.ItemResult::ndcg).average().orElse(0);
        double retrievalRecall = total == 0 ? 0 : results.stream()
            .mapToDouble(RagEvalResponse.ItemResult::retrievalRecall).average().orElse(0);
        double retrievalPrecision = total == 0 ? 0 : results.stream()
            .mapToDouble(RagEvalResponse.ItemResult::retrievalPrecision).average().orElse(0);
        RagEvalResponse response = new RagEvalResponse(null, total, k, round(hitRate), round(mrr),
            round(ndcg), round(retrievalRecall), round(retrievalPrecision), results);
        String runId = saveRun(request, response);
        return new RagEvalResponse(runId, response.total(), response.k(), response.hitRate(),
            response.mrr(), response.ndcg(), response.retrievalRecall(), response.retrievalPrecision(),
            response.items());
    }

    private String saveRun(RagEvalRequest request, RagEvalResponse response) {
        String runId = "rag-eval-" + UUID.randomUUID();
        try {
            int hitCount = (int) response.items().stream().filter(RagEvalResponse.ItemResult::hit).count();
            save(RagEvaluationRunEntity.builder()
                .userId(UserContext.requireUserId())
                .runId(runId)
                .title(titleOrDefault(request.title()))
                .knowledgeBaseIdsJson(objectMapper.writeValueAsString(request.knowledgeBaseIds()))
                .casesJson(objectMapper.writeValueAsString(Map.of(
                    "requestItems", request.items(),
                    "results", response.items(),
                    "ndcg", response.ndcg(),
                    "retrievalRecall", response.retrievalRecall(),
                    "retrievalPrecision", response.retrievalPrecision(),
                    // 兼容历史读取方；新 UI 和报告只使用 retrieval* 字段。
                    "citationHitRate", response.citationHitRate(),
                    "citationCoverage", response.citationCoverage()
                )))
                .totalCases(response.total())
                .hitCount(hitCount)
                .hitRate(response.hitRate())
                .meanReciprocalRank(response.mrr())
                .ndcg(response.ndcg())
                .retrievalRecall(response.retrievalRecall())
                .retrievalPrecision(response.retrievalPrecision())
                .citationHitRate(response.citationHitRate())
                .citationCoverage(response.citationCoverage())
                .topk(response.k())
                .createdAt(LocalDateTime.now())
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

    private void validateGroundTruth(RagEvalRequest.Item item) {
        boolean hasChunkId = item.expectedChunkIds() != null
            && item.expectedChunkIds().stream()
                .anyMatch(value -> value != null && !value.isBlank());
        boolean hasKeyword = item.expectedKeywords() != null
            && item.expectedKeywords().stream()
                .anyMatch(value -> value != null && !value.isBlank());
        if (!hasChunkId && !hasKeyword) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "RAG 评测项必须提供 expectedKeywords 或 expectedChunkIds: " + item.question());
        }
    }

    private RagEvalResponse.ItemResult evaluateItem(RagEvalRequest.Item item, List<TextSegment> retrieved, int k) {
        Set<String> expectedChunkIds = item.expectedChunkIds() == null
            ? Set.of()
            : item.expectedChunkIds().stream()
                .filter(chunkId -> chunkId != null && !chunkId.isBlank())
                .map(String::strip)
                .collect(Collectors.toSet());
        List<KeywordGroup> expectedKeywordGroups = parseKeywordGroups(item.expectedKeywords());
        int expectedEvidenceCount = expectedEvidenceCount(expectedChunkIds, expectedKeywordGroups);
        if (expectedEvidenceCount == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "RAG 评测项必须提供 expectedKeywords 或 expectedChunkIds: " + item.question());
        }

        int firstHitRank = 0;
        List<String> chunkIds = new ArrayList<>();
        List<RagEvalResponse.RetrievedSegment> retrievedSegments = new ArrayList<>();
        double dcg = 0.0;
        int relevantResultCount = 0;
        Set<String> matchedEvidence = new HashSet<>();
        Set<String> seenRetrievedChunks = new HashSet<>();
        int uniqueRetrievedCount = 0;
        for (int i = 0; i < retrieved.size(); i++) {
            TextSegment segment = retrieved.get(i);
            String chunkId = segment.metadata().getString(MetadataKeyConstant.CHUNK_ID);
            chunkIds.add(chunkId);
            retrievedSegments.add(new RagEvalResponse.RetrievedSegment(
                i + 1,
                chunkId,
                parseLong(segment.metadata().getString(MetadataKeyConstant.DOC_ID)),
                snippet(segment.text()),
                parseScore(segment.metadata().toMap().get("SCORE"))));
            if (chunkId != null && !seenRetrievedChunks.add(chunkId)) {
                continue;
            }
            uniqueRetrievedCount++;
            Set<String> segmentEvidence = new HashSet<>();
            if (chunkId != null && expectedChunkIds.contains(chunkId)) {
                segmentEvidence.add("chunk:" + chunkId);
            }
            String text = segment.text() == null ? "" : segment.text().toLowerCase(Locale.ROOT);
            for (KeywordGroup group : expectedKeywordGroups) {
                if (group.matches(text)) {
                    segmentEvidence.add(group.evidenceId());
                }
            }
            boolean relevant = !segmentEvidence.isEmpty();
            segmentEvidence.removeAll(matchedEvidence);
            if (!segmentEvidence.isEmpty()) {
                matchedEvidence.addAll(segmentEvidence);
                int rank = i + 1;
                if (firstHitRank == 0) {
                    firstHitRank = rank;
                }
                dcg += segmentEvidence.size() / log2(rank + 1);
            }
            if (relevant) {
                relevantResultCount++;
            }
        }
        int matchedEvidenceCount = matchedEvidence.size();
        int idealHits = Math.min(k, expectedEvidenceCount);
        double idcg = 0.0;
        for (int i = 1; i <= idealHits; i++) {
            idcg += 1.0 / log2(i + 1);
        }
        double reciprocalRank = firstHitRank == 0 ? 0 : 1.0 / firstHitRank;
        double ndcg = idcg == 0 ? 0 : Math.min(1.0, dcg / idcg);
        double retrievalRecall = Math.min(
            1.0, matchedEvidenceCount * 1.0 / expectedEvidenceCount);
        double retrievalPrecision = uniqueRetrievedCount == 0
            ? 0 : relevantResultCount * 1.0 / uniqueRetrievedCount;
        List<String> matchedKeywords = expectedKeywordGroups.stream()
            .filter(group -> matchedEvidence.contains(group.evidenceId()))
            .map(KeywordGroup::label)
            .toList();
        List<String> missingKeywords = expectedKeywordGroups.stream()
            .filter(group -> !matchedEvidence.contains(group.evidenceId()))
            .map(KeywordGroup::label)
            .toList();
        return new RagEvalResponse.ItemResult(
            item.question(), firstHitRank > 0, firstHitRank, round(reciprocalRank), round(ndcg),
            round(retrievalRecall), round(retrievalPrecision), chunkIds, retrievedSegments,
            expectedEvidenceCount, matchedKeywords, missingKeywords);
    }

    private List<KeywordGroup> parseKeywordGroups(List<String> expectedKeywords) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return List.of();
        }
        List<KeywordGroup> groups = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : expectedKeywords) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String label = raw.strip();
            List<String> aliases = Arrays.stream(label.split("\\|"))
                .map(alias -> alias.strip().toLowerCase(Locale.ROOT))
                .filter(alias -> !alias.isBlank())
                .distinct()
                .toList();
            if (aliases.isEmpty() || !seen.add(label.toLowerCase(Locale.ROOT))) {
                continue;
            }
            groups.add(new KeywordGroup(label, aliases));
        }
        return List.copyOf(groups);
    }

    private int expectedEvidenceCount(Set<String> expectedChunkIds, List<KeywordGroup> expectedKeywords) {
        int chunkCount = expectedChunkIds == null ? 0 : expectedChunkIds.size();
        int keywordCount = expectedKeywords == null ? 0 : expectedKeywords.size();
        return chunkCount + keywordCount;
    }

    private record KeywordGroup(String label, List<String> aliases) {
        String evidenceId() {
            return "keyword:" + label.toLowerCase(Locale.ROOT);
        }

        boolean matches(String text) {
            return aliases.stream().anyMatch(text::contains);
        }
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 220 ? normalized : normalized.substring(0, 220) + "...";
    }

    private Long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseScore(Object value) {
        double score;
        if (value instanceof Number number) {
            score = number.doubleValue();
        } else if (value instanceof String text) {
            try {
                score = Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isFinite(score) ? round(score) : null;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
