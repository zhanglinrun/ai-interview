package com.linrun.interview.rag.service;

import com.linrun.interview.rag.mapper.RagAnswerSnapshotMapper;
import com.linrun.interview.rag.mapper.RagTraceCandidateMapper;
import com.linrun.interview.rag.mapper.RagTraceCitationMapper;
import com.linrun.interview.rag.mapper.RagTraceRunMapper;
import com.linrun.interview.rag.mapper.RagTraceStageMapper;
import com.linrun.interview.rag.model.RagAnswerSnapshotEntity;
import com.linrun.interview.rag.model.RagTraceCandidateEntity;
import com.linrun.interview.rag.model.RagTraceCitationEntity;
import com.linrun.interview.rag.model.RagTraceDetail;
import com.linrun.interview.rag.model.RagTraceRunEntity;
import com.linrun.interview.rag.model.RagTraceStageEntity;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.rag.model.RagQueryTrace;
import com.linrun.interview.rag.model.RagSourceDTO;
import com.linrun.interview.business.vo.InterviewEvidence;
import com.linrun.interview.business.vo.InterviewEvidence.Bundle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RAG 阶段化 trace 写入器。
 *
 * <p>它是非阻断式 sink：数据库或 JSON 序列化失败只记录告警，不影响回答流。
 * 输入摘要统一截断，避免把完整简历、Prompt 或大模型响应无限写入数据库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagTraceRecorder {

    private static final int SUMMARY_LIMIT = 4000;
    private static final int METADATA_LIMIT = 8000;

    private final RagTraceRunMapper runMapper;
    private final RagTraceStageMapper stageMapper;
    private final RagTraceCandidateMapper candidateMapper;
    private final RagTraceCitationMapper citationMapper;
    private final RagAnswerSnapshotMapper answerMapper;
    private final ObjectMapper objectMapper;

    public String recordSnapshot(String traceId, Long userId, String sessionId,
                               List<Long> knowledgeBaseIds,
                               String question, RagQueryTrace trace,
                               List<RagSourceDTO> sources, String answer,
                               Double confidence, List<Integer> invalidCitations,
                               long latencyMs) {
        return recordSnapshot(traceId, userId, sessionId, null, null, knowledgeBaseIds, question, trace,
            sources, answer, confidence, invalidCitations, latencyMs);
    }

    /** Records the deterministic interview evidence lookup as its own RagRun. */
    public String recordInterviewEvidence(String traceId, Long userId, String sessionId,
                                          String agentRunId, String query, Bundle bundle,
                                          boolean failed, long latencyMs) {
        String ragRunId = "rag-" + UUID.randomUUID();
        if (traceId == null || traceId.isBlank()) {
            return ragRunId;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            String safeQuery = limit(query, SUMMARY_LIMIT);
            runMapper.insert(RagTraceRunEntity.builder()
                .ragRunId(ragRunId)
                .traceId(traceId)
                .agentRunId(agentRunId)
                .rootSpanId("rag-span-" + UUID.randomUUID())
                .userId(userId)
                .sessionId(sessionId == null || sessionId.isBlank() ? "default" : sessionId)
                .question(safeQuery)
                .status(failed ? "DEGRADED" : "COMPLETED")
                .routeSource("interview.evidence")
                .latencyMs(Math.max(0L, latencyMs))
                .degradedReason(failed ? "retrieval_failed" : null)
                .createdAt(now)
                .completedAt(now)
                .build());
            recordStage(ragRunId, "RETRIEVAL", failed ? "DEGRADED" : "COMPLETED",
                safeQuery, "knowledge_base", bundle == null ? "" :
                    "candidates=" + bundle.candidates().size() + ", selected="
                        + bundle.promptEvidence().size(), null, now, null,
                failed ? "retrieval_failed" : null);
            if (bundle != null) {
                for (InterviewEvidence evidence : bundle.candidates()) {
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("source", valueOrEmpty(evidence.source()));
                    metadata.put("category", valueOrEmpty(evidence.category()));
                    candidateMapper.insert(RagTraceCandidateEntity.builder()
                        .ragRunId(ragRunId)
                        .stage("RETRIEVAL")
                        .rankNo(bundle.candidates().indexOf(evidence) + 1)
                        .sourceType("knowledge_base")
                        .documentId(evidence.knowledgeBaseId() == null ? null
                            : String.valueOf(evidence.knowledgeBaseId()))
                        .segmentId(evidence.chunkId())
                        .evidenceId(evidence.id())
                        .score(evidence.score())
                        .snippet(limit(evidence.snippet(), 1000))
                        .metadataJson(json(metadata))
                        .permissionAllowed(true)
                        .versionMatched(true)
                        .createdAt(now)
                        .build());
                }
            }
            return ragRunId;
        } catch (Exception e) {
            log.warn("写入面试证据 RAG Run 失败: traceId={}, reason={}", traceId, e.getMessage());
            return ragRunId;
        }
    }

    public String recordSnapshot(String traceId, Long userId, String sessionId,
                               String agentRunId, String requestedRagRunId,
                               List<Long> knowledgeBaseIds,
                               String question, RagQueryTrace trace,
                               List<RagSourceDTO> sources, String answer,
                               Double confidence, List<Integer> invalidCitations,
                               long latencyMs) {
        if (traceId == null || traceId.isBlank()) {
            return requestedRagRunId;
        }
        String ragRunId = requestedRagRunId == null || requestedRagRunId.isBlank()
            ? "rag-" + UUID.randomUUID() : requestedRagRunId;
        try {
            LocalDateTime now = LocalDateTime.now();
            runMapper.insert(RagTraceRunEntity.builder()
                .ragRunId(ragRunId)
                .traceId(traceId)
                .agentRunId(agentRunId)
                .rootSpanId("rag-span-" + UUID.randomUUID())
                .userId(userId)
                .sessionId(sessionId == null || sessionId.isBlank() ? "default" : sessionId)
                .question(limit(question, SUMMARY_LIMIT))
                .status(trace != null && !trace.degradedReasons().isEmpty()
                    ? "DEGRADED" : "COMPLETED")
                .routeSource(trace == null ? null : trace.routeSource())
                .routeIntent(trace == null ? null : trace.routeIntent())
                .latencyMs(latencyMs)
                .degradedReason(trace == null ? null : firstFallback(trace.degradedReasons()))
                .answerSummary(limit(answer, SUMMARY_LIMIT))
                .createdAt(now)
                .completedAt(now)
                .build());

            if (trace != null) {
                recordStage(ragRunId, "INTENT", "route", trace.routeIntent(),
                    trace.routeSource(), trace.routeReasoning(), trace.routeConfidence(), now);
                recordStage(ragRunId, "REWRITE", "rewritten", trace.rewrittenQuestion(),
                    null, json(trace.decomposedQueries()), null, now);
                recordCandidates(ragRunId, "RETRIEVAL", trace.retrieved());
                recordCandidates(ragRunId, "RERANK", trace.reranked());
                recordStage(ragRunId, "CITATION", "evidence", json(trace.evidenceRefs()),
                    trace.evidenceStatus() == null ? null : trace.evidenceStatus().name(),
                    json(trace.degradedReasons()), null, now, json(trace.evidenceScope()),
                    firstFallback(trace.degradedReasons()));
            }
            recordCitations(ragRunId, sources, confidence, invalidCitations);
            answerMapper.insert(RagAnswerSnapshotEntity.builder()
                .ragRunId(ragRunId)
                .answer(limit(answer, SUMMARY_LIMIT))
                .groundedStatus(trace == null || trace.evidenceStatus() == null
                    ? null : trace.evidenceStatus().name())
                .confidence(confidence)
                .invalidCitationsJson(json(invalidCitations))
                .tokenCount(answer == null ? 0 : answer.length())
                .createdAt(now)
                .build());
            return ragRunId;
        } catch (Exception e) {
            log.warn("写入阶段化 RAG Trace 失败: traceId={}, reason={}", traceId, e.getMessage());
            return ragRunId;
        }
    }

    public RagTraceDetail get(String traceId, Long userId) {
        RagTraceRunEntity run = runMapper.selectOne(Wrappers.<RagTraceRunEntity>lambdaQuery()
            .eq(RagTraceRunEntity::getTraceId, traceId)
            .eq(RagTraceRunEntity::getUserId, userId)
            .orderByDesc(RagTraceRunEntity::getCreatedAt)
            .last("LIMIT 1"));
        if (run == null) {
            return null;
        }
        return new RagTraceDetail(
            run,
            stageMapper.selectList(Wrappers.<RagTraceStageEntity>lambdaQuery()
                .eq(RagTraceStageEntity::getRagRunId, run.getRagRunId())
                .orderByAsc(RagTraceStageEntity::getStartedAt)),
            candidateMapper.selectList(Wrappers.<RagTraceCandidateEntity>lambdaQuery()
                .eq(RagTraceCandidateEntity::getRagRunId, run.getRagRunId())
                .orderByAsc(RagTraceCandidateEntity::getStage)
                .orderByAsc(RagTraceCandidateEntity::getRankNo)),
            citationMapper.selectList(Wrappers.<RagTraceCitationEntity>lambdaQuery()
                .eq(RagTraceCitationEntity::getRagRunId, run.getRagRunId())
                .orderByAsc(RagTraceCitationEntity::getCitationIndex)),
            answerMapper.selectOne(Wrappers.<RagAnswerSnapshotEntity>lambdaQuery()
                .eq(RagAnswerSnapshotEntity::getRagRunId, run.getRagRunId())
                .orderByDesc(RagAnswerSnapshotEntity::getCreatedAt)
                .last("LIMIT 1")));
    }

    public List<RagTraceRunEntity> list(Long userId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return runMapper.selectList(Wrappers.<RagTraceRunEntity>lambdaQuery()
            .eq(RagTraceRunEntity::getUserId, userId)
            .orderByDesc(RagTraceRunEntity::getCreatedAt)
            .last("LIMIT " + safeLimit));
    }

    private void recordStage(String ragRunId, String stage, String status, String input,
                             String source, String output, Double confidence, LocalDateTime now) {
        recordStage(ragRunId, stage, status, input, source, output, confidence, now, null, null);
    }

    private void recordStage(String ragRunId, String stage, String status, String input,
                             String source, String output, Double confidence, LocalDateTime now,
                             String filterJson, String fallbackReason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (confidence != null) {
            metadata.put("confidence", confidence);
        }
        stageMapper.insert(RagTraceStageEntity.builder()
            .ragRunId(ragRunId)
            .stage(stage)
            .status(status)
            .dataSource(source)
            .inputSummary(limit(input, SUMMARY_LIMIT))
            .outputSummary(limit(output, SUMMARY_LIMIT))
            .metadataJson(json(metadata))
            .filterJson(limit(filterJson, METADATA_LIMIT))
            .fallbackReason(limit(fallbackReason, 1000))
            .startedAt(now)
            .completedAt(now)
            .latencyMs(0L)
            .build());
    }

    private void recordCandidates(String ragRunId, String stage, List<RagQueryTrace.TraceContent> contents) {
        if (contents == null) {
            return;
        }
        for (RagQueryTrace.TraceContent content : contents) {
            candidateMapper.insert(RagTraceCandidateEntity.builder()
                .ragRunId(ragRunId)
                .stage(stage)
                .rankNo(content.rank())
                .sourceType(content.dataDomain())
                .documentId(content.docId())
                .segmentId(content.chunkId())
                .evidenceId(content.evidenceId())
                .score(content.score())
                .rerankScore(content.rerankScore())
                .snippet(limit(content.snippet(), 1000))
                .metadataJson(json(Map.of(
                    "resourceId", valueOrEmpty(content.resourceId()),
                    "resourceVersion", valueOrEmpty(content.resourceVersion()),
                    "sourceLocator", valueOrEmpty(content.sourceLocator()),
                    "contentHash", valueOrEmpty(content.contentHash()))))
                .createdAt(LocalDateTime.now())
                .build());
        }
    }

    private void recordCitations(String ragRunId, List<RagSourceDTO> sources,
                                 Double confidence, List<Integer> invalidCitations) {
        if (sources == null) {
            return;
        }
        for (int i = 0; i < sources.size(); i++) {
            RagSourceDTO source = sources.get(i);
            int citationIndex = i + 1;
            citationMapper.insert(RagTraceCitationEntity.builder()
                .ragRunId(ragRunId)
                .citationIndex(citationIndex)
                .evidenceId(source.knowledgeBaseId() == null ? null : String.valueOf(source.knowledgeBaseId()))
                .sourceLocator(limit(source.sourceName(), SUMMARY_LIMIT))
                .cited(source.cited())
                .valid(invalidCitations == null || !invalidCitations.contains(citationIndex))
                .confidence(confidence)
                .createdAt(LocalDateTime.now())
                .build());
        }
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return limit(objectMapper.writeValueAsString(value), METADATA_LIMIT);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstFallback(List<String> reasons) {
        return reasons == null || reasons.isEmpty() ? null : reasons.getFirst();
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
