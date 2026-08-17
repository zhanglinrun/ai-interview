package com.linrun.interview.chat.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * v1 SSE 的统一事件 envelope。旧的前缀事件只作为内部流片段，协议层不再依赖字符串拼接。
 */
public record SseEventEnvelope(
    String traceId,
    String ragRunId,
    long sequence,
    String stage,
    String event,
    Instant timestamp,
    JsonNode payload
) {
    public static SseEventEnvelope fromRaw(String traceId, long sequence, String raw,
                                           ObjectMapper objectMapper) {
        if ("__sse_start__".equals(raw)) {
        return text(traceId, sequence, "start", "trace_start", "RAG trace started", objectMapper);
        }
        if ("__sse_done__".equals(raw)) {
            return text(traceId, sequence, "complete", "done", "完成", objectMapper);
        }
        if (raw != null && raw.startsWith("__sse_error__:")) {
            return text(traceId, sequence, "complete", "error",
                raw.substring("__sse_error__:".length()), objectMapper);
        }
        String stage = "generation";
        String event = "token";
        String value = raw == null ? "" : raw;
        String prefix = null;
        if (value.startsWith("progress:")) {
            String progress = value.substring("progress:".length());
            stage = classifyProgressStage(progress);
            event = "progress";
            prefix = "progress:";
        } else if (value.startsWith("intent:")) {
            event = "intent";
            stage = "intent";
            prefix = "intent:";
        } else if (value.startsWith("rewritten:")) {
            event = "rewrite";
            stage = "rewrite";
            prefix = "rewritten:";
        } else if (value.startsWith("route:")) {
            event = "route";
            stage = "route";
            prefix = "route:";
        } else if (value.startsWith("reference:")) {
            event = "retrieval";
            stage = "evidence";
            prefix = "reference:";
        } else if (value.startsWith("citation:")) {
            event = "citation";
            stage = "citation";
            prefix = "citation:";
        } else if (value.startsWith("card:") || value.startsWith("card_choice:")) {
            event = "agent_step";
            stage = "interaction";
            prefix = value.startsWith("card:") ? "card:" : "card_choice:";
        }
        if (prefix != null) {
            value = value.substring(prefix.length());
        }
        JsonNode payload;
        if (prefix != null && (value.startsWith("{") || value.startsWith("["))) {
            try {
                payload = objectMapper.readTree(value);
            } catch (Exception ignored) {
                payload = objectMapper.getNodeFactory().textNode(value);
            }
        } else {
            payload = objectMapper.getNodeFactory().textNode(value);
        }
        return new SseEventEnvelope(traceId, "rag-" + traceId, sequence, stage, event,
            Instant.now(), payload);
    }

    private static String classifyProgressStage(String progress) {
        if (progress == null) {
            return "pipeline";
        }
        if (progress.contains("重排") || progress.contains("精排") || progress.contains("RRF")) {
            return "rerank";
        }
        if (progress.contains("引用")) {
            return "citation";
        }
        if (progress.contains("生成")) {
            return "generation";
        }
        return "pipeline";
    }

    private static SseEventEnvelope text(String traceId, long sequence, String stage,
                                         String event, String value, ObjectMapper mapper) {
        return new SseEventEnvelope(traceId, "rag-" + traceId, sequence, stage, event,
            Instant.now(), mapper.getNodeFactory().textNode(value));
    }
}
