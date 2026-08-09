package com.linrun.interview.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.rag.mapper.RagQueryTraceMapper;
import com.linrun.interview.rag.model.RagQueryTraceDTO;
import com.linrun.interview.rag.model.RagQueryTraceEntity;
import com.linrun.interview.rag.model.RagSourceDTO;
import com.linrun.interview.rag.model.RagQueryTrace;
import com.linrun.interview.rag.service.RagTraceRecorder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class RagQueryTraceService
    extends ServiceImpl<RagQueryTraceMapper, RagQueryTraceEntity> {

  private final RagQueryTraceMapper ragQueryTraceMapper;
  private final ObjectMapper objectMapper;
  private final RagTraceRecorder traceRecorder;

  @org.springframework.beans.factory.annotation.Autowired
  public RagQueryTraceService(RagQueryTraceMapper ragQueryTraceMapper,
                              ObjectMapper objectMapper,
                              RagTraceRecorder traceRecorder) {
    this.ragQueryTraceMapper = ragQueryTraceMapper;
    this.objectMapper = objectMapper;
    this.traceRecorder = traceRecorder;
    this.baseMapper = ragQueryTraceMapper;
  }

  /** 保留给不启动 Spring 容器的纯单元测试。 */
  public RagQueryTraceService(RagQueryTraceMapper ragQueryTraceMapper, ObjectMapper objectMapper) {
    this(ragQueryTraceMapper, objectMapper, null);
  }

  public String save(Long userId, List<Long> knowledgeBaseIds, String question, RagQueryTrace trace,
                     List<RagSourceDTO> sources, String answer, Double confidence,
                     List<Integer> invalidCitations, long latencyMs) {
    return saveWithTraceId(null, userId, null, knowledgeBaseIds, question, trace, sources, answer,
        confidence, invalidCitations, latencyMs);
  }

  public String save(Long userId, String sessionId, List<Long> knowledgeBaseIds, String question,
                     RagQueryTrace trace, List<RagSourceDTO> sources, String answer,
                     Double confidence, List<Integer> invalidCitations, long latencyMs) {
    return saveWithTraceId(null, userId, sessionId, knowledgeBaseIds, question, trace, sources,
        answer, confidence, invalidCitations, latencyMs);
  }

  /**
   * 使用调用方已分配的 traceId 持久化，保证 SSE envelope 与数据库 Trace 可以一一回放。
   * 离线/同步调用继续使用旧的便捷入口，由本方法生成 ID。
   */
  public String saveWithTraceId(String requestedTraceId, Long userId, String sessionId,
                                List<Long> knowledgeBaseIds, String question, RagQueryTrace trace,
                                List<RagSourceDTO> sources, String answer, Double confidence,
                                List<Integer> invalidCitations, long latencyMs) {
    String traceId = requestedTraceId == null || requestedTraceId.isBlank()
        ? "rag-trace-" + UUID.randomUUID() : requestedTraceId;
    // One structured RAG request owns one run; keeping the ID derivable lets
    // SSE clients correlate the envelope before the asynchronous final write.
    String ragRunId = "rag-" + traceId;
    try {
      save(RagQueryTraceEntity.builder()
        .userId(userId)
        .traceId(traceId)
        .ragRunId(ragRunId)
        .question(question)
        .rewrittenQuestion(trace != null ? trace.rewrittenQuestion() : null)
        .decomposedQueriesJson(trace != null && !trace.decomposedQueries().isEmpty()
            ? writeJson(trace.decomposedQueries()) : null)
        .cragGrade(trace != null ? trace.cragGrade() : null)
        .cragAction(trace != null ? trace.cragAction() : null)
        .routeSource(trace != null ? trace.routeSource() : null)
        .routeIntent(trace != null ? trace.routeIntent() : null)
        .routeConfidence(trace != null ? trace.routeConfidence() : null)
        .routeReasoning(trace != null ? trace.routeReasoning() : null)
        .knowledgeBaseIdsJson(writeJson(knowledgeBaseIds))
        .evidenceScopeJson(trace != null && trace.evidenceScope() != null
            ? writeJson(trace.evidenceScope()) : null)
        .evidenceStatus(trace != null && trace.evidenceStatus() != null
            ? trace.evidenceStatus().name() : null)
        .evidenceRefsJson(trace != null && !trace.evidenceRefs().isEmpty()
            ? writeJson(trace.evidenceRefs()) : null)
        .degradedReasonsJson(trace != null && !trace.degradedReasons().isEmpty()
            ? writeJson(trace.degradedReasons()) : null)
        .retrievedJson(writeJson(trace != null ? trace.retrieved() : List.of()))
        .rerankedJson(writeJson(trace != null ? trace.reranked() : List.of()))
        .finalSourcesJson(writeJson(sources))
        .answer(answer)
        .confidence(confidence)
        .invalidCitationsJson(writeJson(invalidCitations))
        .latencyMs(latencyMs)
        .createdAt(LocalDateTime.now())
        .build());
      if (traceRecorder != null) {
        traceRecorder.recordSnapshot(traceId, userId, sessionId, null, ragRunId,
            knowledgeBaseIds, question, trace, sources, answer, confidence,
            invalidCitations, latencyMs);
      }
      return traceId;
    } catch (Exception e) {
      log.warn("保存 RAG Trace 失败: {}", e.getMessage(), e);
      return traceId;
    }
  }

  public List<RagQueryTraceDTO> listRecent(int limit) {
    int size = Math.min(Math.max(limit, 1), 50);
    return ragQueryTraceMapper.selectList(
        Wrappers.<RagQueryTraceEntity>lambdaQuery()
          .eq(RagQueryTraceEntity::getUserId, UserContext.requireUserId())
          .orderByDesc(RagQueryTraceEntity::getCreatedAt)
          .last("LIMIT " + size))
      .stream()
      .map(RagQueryTraceDTO::from)
      .toList();
  }

  public RagQueryTraceDTO get(String traceId) {
    RagQueryTraceEntity trace = getOne(Wrappers.<RagQueryTraceEntity>lambdaQuery()
        .eq(RagQueryTraceEntity::getTraceId, traceId)
        .eq(RagQueryTraceEntity::getUserId, UserContext.requireUserId())
        // trace_id is intentionally non-unique: one HTTP trace can contain
        // several RAG runs.  Keep the legacy endpoint backward compatible by
        // returning the newest summary only; the unified endpoint exposes all.
        .orderByDesc(RagQueryTraceEntity::getCreatedAt)
        .last("LIMIT 1"));
    if (trace == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "Trace 不存在: " + traceId);
    }
    return RagQueryTraceDTO.from(trace);
  }

  private String writeJson(Object value) throws JsonProcessingException {
    return objectMapper.writeValueAsString(value == null ? List.of() : value);
  }
}
