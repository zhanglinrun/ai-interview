package com.linrun.interview.modules.knowledgebase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.knowledgebase.model.RagQueryTraceDTO;
import com.linrun.interview.modules.knowledgebase.model.RagQueryTraceEntity;
import com.linrun.interview.modules.knowledgebase.model.RagSourceDTO;
import com.linrun.interview.modules.knowledgebase.rag.RagQueryTrace;
import com.linrun.interview.modules.knowledgebase.repository.RagQueryTraceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagQueryTraceService {

    private final RagQueryTraceRepository repository;
    private final ObjectMapper objectMapper;

    public String save(Long userId, List<Long> knowledgeBaseIds, String question, RagQueryTrace trace,
                       List<RagSourceDTO> sources, String answer, Double confidence,
                       List<Integer> invalidCitations, long latencyMs) {
        String traceId = "rag-trace-" + UUID.randomUUID();
        try {
            repository.save(RagQueryTraceEntity.builder()
                .userId(userId)
                .traceId(traceId)
                .question(question)
                .rewrittenQuestion(trace != null ? trace.rewrittenQuestion() : null)
                .routeStrategy(trace != null ? trace.routeStrategy() : null)
                .routeReasoning(trace != null ? trace.routeReasoning() : null)
                .knowledgeBaseIdsJson(writeJson(knowledgeBaseIds))
                .retrievedJson(writeJson(trace != null && !trace.reranked().isEmpty()
                    ? trace.reranked() : trace != null ? trace.retrieved() : List.of()))
                .finalSourcesJson(writeJson(sources))
                .answer(answer)
                .confidence(confidence)
                .invalidCitationsJson(writeJson(invalidCitations))
                .latencyMs(latencyMs)
                .build());
            return traceId;
        } catch (Exception e) {
            log.warn("保存 RAG Trace 失败: {}", e.getMessage(), e);
            return traceId;
        }
    }

    public List<RagQueryTraceDTO> listRecent(int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        return repository.findByUserIdOrderByCreatedAtDesc(
                UserContext.requireUserId(), PageRequest.of(0, size)).stream()
            .map(RagQueryTraceDTO::from)
            .toList();
    }

    public RagQueryTraceDTO get(String traceId) {
        return repository.findByUserIdAndTraceId(UserContext.requireUserId(), traceId)
            .map(RagQueryTraceDTO::from)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Trace 不存在: " + traceId));
    }

    private String writeJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value == null ? List.of() : value);
    }
}
