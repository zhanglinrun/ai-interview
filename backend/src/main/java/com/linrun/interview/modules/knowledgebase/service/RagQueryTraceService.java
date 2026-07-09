package com.linrun.interview.modules.knowledgebase.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.knowledgebase.mapper.RagQueryTraceMapper;
import com.linrun.interview.modules.knowledgebase.model.RagQueryTraceDTO;
import com.linrun.interview.modules.knowledgebase.model.RagQueryTraceEntity;
import com.linrun.interview.modules.knowledgebase.model.RagSourceDTO;
import com.linrun.interview.modules.knowledgebase.rag.RagQueryTrace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagQueryTraceService {

  private final RagQueryTraceMapper ragQueryTraceMapper;
  private final ObjectMapper objectMapper;

  public String save(Long userId, List<Long> knowledgeBaseIds, String question, RagQueryTrace trace,
                     List<RagSourceDTO> sources, String answer, Double confidence,
                     List<Integer> invalidCitations, long latencyMs) {
    String traceId = "rag-trace-" + UUID.randomUUID();
    try {
      MapperUtils.save(ragQueryTraceMapper, RagQueryTraceEntity.builder()
        .userId(userId)
        .traceId(traceId)
        .question(question)
        .rewrittenQuestion(trace != null ? trace.rewrittenQuestion() : null)
        .routeStrategy(trace != null ? trace.routeStrategy() : null)
        .routeReasoning(trace != null ? trace.routeReasoning() : null)
        .decomposedQueriesJson(trace != null && !trace.decomposedQueries().isEmpty()
            ? writeJson(trace.decomposedQueries()) : null)
        .cragGrade(trace != null ? trace.cragGrade() : null)
        .cragAction(trace != null ? trace.cragAction() : null)
        .graphAttempted(trace != null && trace.graphAttempted())
        .graphHit(trace != null && trace.graphHit())
        .graphResult(trace != null ? trace.graphResult() : null)
        .knowledgeBaseIdsJson(writeJson(knowledgeBaseIds))
        .retrievedJson(writeJson(trace != null ? trace.retrieved() : List.of()))
        .rerankedJson(writeJson(trace != null ? trace.reranked() : List.of()))
        .finalSourcesJson(writeJson(sources))
        .answer(answer)
        .confidence(confidence)
        .invalidCitationsJson(writeJson(invalidCitations))
        .latencyMs(latencyMs)
        .createdAt(LocalDateTime.now())
        .build());
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
    return EntityQueries.selectOne(ragQueryTraceMapper, RagQueryTraceEntity::getTraceId, traceId)
      .filter(t -> UserContext.requireUserId().equals(t.getUserId()))
      .map(RagQueryTraceDTO::from)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Trace 不存在: " + traceId));
  }

  private String writeJson(Object value) throws JsonProcessingException {
    return objectMapper.writeValueAsString(value == null ? List.of() : value);
  }
}
