package com.linrun.interview.business.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.vo.InterviewEvidence.Bundle;
import com.linrun.interview.infra.observability.TraceContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutor {
  private final ToolRegistry registry;
  private final ToolCacheStore cacheStore;
  private final ToolCircuitBreakerStore circuitBreaker;
  private final ToolAuditService auditService;
  private final ObjectMapper objectMapper;
  private final AgentTraceService agentTraceService;

  public <T> ToolResult<T> execute(String toolName, ToolExecutionContext context,
                                    Map<String, Object> input, Class<T> resultType) {
    ToolRegistry.RegisteredTool registered = registry.get(toolName);
    if (registered == null) {
      return ToolResult.rejected("TOOL_NOT_FOUND", "工具不存在: " + toolName);
    }
    ToolDescriptor descriptor = registered.descriptor();
    String role = context == null ? null : context.role();
    if (role == null || !descriptor.allowedRoles().contains(role)) {
      ToolResult<T> result = ToolResult.rejected("TOOL_ROLE_REJECTED", "当前角色不能调用工具: " + toolName);
      recordOutcome(toolName, context, result, serializedSummary(input), 0L);
      return result;
    }
    Map<String, Object> safeInput = input == null ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    if (serializedLength(safeInput) > descriptor.maxInputChars()) {
      ToolResult<T> result = ToolResult.rejected("TOOL_INPUT_TOO_LARGE", "工具输入超过长度限制");
      recordOutcome(toolName, context, result, serializedSummary(safeInput), 0L);
      return result;
    }
    String cacheKey = cacheKey(toolName, descriptor, context, safeInput);
    long start = System.nanoTime();
    boolean cacheHit = false;
    int retries = 0;
    try {
      if (isCircuitOpen(toolName)) {
        ToolResult<T> result = ToolResult.<T>circuitOpen("工具熔断中")
            .measured(elapsed(start), false, 0);
        recordOutcome(toolName, context, result, serializedSummary(safeInput), result.latencyMs());
        return result;
      }
      if (descriptor.cacheable()) {
        String cached = getCacheQuietly(cacheKey);
        if (cached != null) {
          T value = read(cached, resultType);
          ToolResult<T> result = ToolResult.success(value, "cache_hit")
              .measured(elapsed(start), true, 0);
          recordOutcome(toolName, context, result, serializedSummary(safeInput), result.latencyMs());
          return result;
        }
      }

      ToolResult<?> raw = invoke(registered.handler(), context, safeInput, descriptor.timeoutMs());
      while ((raw.status() == ToolStatus.FAILED || raw.status() == ToolStatus.TIMEOUT)
          && descriptor.idempotent() && retries < 1) {
        retries++;
        raw = invoke(registered.handler(), context, safeInput, descriptor.timeoutMs());
      }
      ToolResult<T> result = cast(raw, resultType).measured(elapsed(start), cacheHit, retries);
      if (result.status() == ToolStatus.SUCCESS || result.status() == ToolStatus.EMPTY) {
        recordSuccessQuietly(toolName);
        if (descriptor.cacheable() && result.data() != null && result.status() == ToolStatus.SUCCESS) {
          putCacheQuietly(cacheKey, write(result.data()));
        }
      } else if (result.status() == ToolStatus.FAILED || result.status() == ToolStatus.TIMEOUT
          || result.status() == ToolStatus.DEGRADED || result.status() == ToolStatus.CIRCUIT_OPEN) {
        recordFailureQuietly(toolName);
      }
      recordOutcome(toolName, context, result, serializedSummary(safeInput), result.latencyMs());
      return result;
    } catch (Exception e) {
      recordFailureQuietly(toolName);
      ToolResult<T> result = new ToolResult<>(ToolStatus.FAILED, null,
          "工具执行失败", "TOOL_EXECUTION_FAILED", safeMessage(e), elapsed(start),
          cacheHit, retries, null);
      recordOutcome(toolName, context, result, serializedSummary(safeInput), result.latencyMs());
      return result;
    }
  }

  private ToolResult<?> invoke(ToolHandler handler, ToolExecutionContext context,
                               Map<String, Object> input, long timeoutMs) {
    try {
      java.util.function.Supplier<ToolResult<?>> supplier =
          () -> handler.execute(context, input);
      return CompletableFuture.supplyAsync(TraceContext.wrap(supplier))
          .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
          .join();
    } catch (java.util.concurrent.CompletionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof java.util.concurrent.TimeoutException) {
        return new ToolResult<>(ToolStatus.TIMEOUT, null, "工具执行超时",
            "TOOL_TIMEOUT", "timeout", timeoutMs, false, 0, null);
      }
      return new ToolResult<>(ToolStatus.FAILED, null, "工具执行失败",
          "TOOL_FAILED", safeMessage(cause), 0L, false, 0, null);
    }
  }

  private <T> ToolResult<T> cast(ToolResult<?> result, Class<T> type) {
    if (result.data() == null || type == null || type.isInstance(result.data())) {
      @SuppressWarnings("unchecked")
      ToolResult<T> converted = (ToolResult<T>) result;
      return converted;
    }
    return new ToolResult<>(ToolStatus.FAILED, null, "工具结果类型不匹配",
        "TOOL_RESULT_TYPE", type.getName(), result.latencyMs(), result.cacheHit(),
        result.retryCount(), result.ragRunId());
  }

  private <T> T read(String value, Class<T> type) throws JsonProcessingException {
    if (type == String.class) {
      return type.cast(value);
    }
    return objectMapper.readValue(value, type);
  }

  private String write(Object value) throws JsonProcessingException {
    return objectMapper.writeValueAsString(value);
  }

  private String cacheKey(String toolName, ToolDescriptor descriptor, ToolExecutionContext context,
                          Map<String, Object> input) {
    Map<String, Object> canonical = canonicalInput(input);
    String resourceVersion = registry.cacheDiscriminator(toolName, context, canonical);
    return "agent:tool:cache:" + descriptor.name() + ":" + descriptor.version() + ":"
        + Objects.toString(context == null ? null : context.userId(), "anonymous") + ":"
        + Objects.toString(context == null ? null : context.role(), "unknown") + ":"
        + resourceVersion + ":" + Integer.toHexString(serializedSummary(canonical).hashCode());
  }

  /** Stable cache identity: normalize free-text queries and treat KB ids as a set. */
  private Map<String, Object> canonicalInput(Map<String, Object> input) {
    Map<String, Object> canonical = new TreeMap<>();
    if (input == null) {
      return canonical;
    }
    input.forEach((key, value) -> {
      String normalizedKey = key == null ? "" : key;
      if ("query".equalsIgnoreCase(normalizedKey) && value != null) {
        canonical.put(normalizedKey, String.valueOf(value)
            .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT));
      } else if ("knowledgeBaseIds".equalsIgnoreCase(normalizedKey)
          && value instanceof java.util.Collection<?> values) {
        List<String> ids = new ArrayList<>();
        values.stream().filter(Objects::nonNull).map(String::valueOf).forEach(ids::add);
        ids.sort(Comparator.naturalOrder());
        canonical.put(normalizedKey, ids);
      } else {
        canonical.put(normalizedKey, value);
      }
    });
    return canonical;
  }

  private <T> void recordOutcome(String toolName, ToolExecutionContext context,
                                 ToolResult<T> result, String inputSummary, long latencyMs) {
    auditService.recordQuietly(context, toolName, result, inputSummary, latencyMs);
    writeOrchestratorToolSpan(toolName, context, inputSummary, result, latencyMs);
  }

  private <T> void writeOrchestratorToolSpan(String toolName, ToolExecutionContext context,
                                             String inputSummary, ToolResult<T> result,
                                             long latencyMs) {
    if (agentTraceService == null || context == null
        || context.agentRunId() == null || context.agentRunId().isBlank()) {
      return;
    }
    if ("resume.read".equals(toolName) || "readResume".equals(toolName)) {
      return;
    }
    String observation = summarizeToolResult(toolName, result);
    AgentRunHandle run = new AgentRunHandle(
        context.agentRunId(),
        context.traceId(),
        null,
        context.sessionId(),
        context.userId(),
        "question",
        context.spanId(),
        LocalDateTime.now());
    agentTraceService.appendQuietly(run, new AgentSpanRecord(
        "span-tool-" + UUID.randomUUID(),
        context.spanId(),
        context.role() == null ? "orchestrator" : context.role().toLowerCase(Locale.ROOT),
        toolName,
        inputSummary,
        observation,
        result.status() == ToolStatus.FAILED || result.status() == ToolStatus.TIMEOUT
            ? "FAILED" : "COMPLETED",
        latencyMs,
        AgentSpanMetadata.write(objectMapper, AgentSpanMetadata.KIND_TOOL,
            null, null, null, "agent.tool"),
        0,
        context.questionIndex()));
  }

  private <T> String summarizeToolResult(String toolName, ToolResult<T> result) {
    if (result == null) {
      return toolName + " completed";
    }
    if (result.data() instanceof Bundle bundle) {
      int candidates = bundle.candidates() == null ? 0 : bundle.candidates().size();
      if (candidates == 0) {
        return result.status() == ToolStatus.EMPTY || result.status() == ToolStatus.SUCCESS
            ? "empty result / skipped"
            : result.summary() == null ? "empty result / skipped" : result.summary();
      }
      return "candidates=" + candidates
          + " prompt=" + (bundle.promptEvidence() == null ? 0 : bundle.promptEvidence().size());
    }
    if (result.summary() != null && !result.summary().isBlank()) {
      return result.summary();
    }
    return result.status() == null ? toolName + " completed" : result.status().name();
  }

  private String serializedSummary(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return String.valueOf(value);
    }
  }

  private int serializedLength(Object value) {
    return serializedSummary(value).length();
  }

  private long elapsed(long start) {
    return Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
  }

  private String safeMessage(Throwable error) {
    String message = error == null ? null : error.getMessage();
    return message == null ? "unknown" : message.length() > 255 ? message.substring(0, 255) : message;
  }

  private boolean isCircuitOpen(String toolName) {
    try {
      return circuitBreaker.isOpen(toolName);
    } catch (Exception e) {
      // Redis is an optimization for the gateway.  If it is unavailable,
      // execute the bounded tool call instead of blocking the interview.
      log.warn("工具熔断状态读取失败，按关闭处理: tool={}, reason={}", toolName, e.getMessage());
      return false;
    }
  }

  private String getCacheQuietly(String key) {
    try {
      return cacheStore.get(key);
    } catch (Exception e) {
      log.warn("工具缓存读取失败，按未命中处理: reason={}", e.getMessage());
      return null;
    }
  }

  private void putCacheQuietly(String key, String value) {
    try {
      cacheStore.put(key, value, Duration.ofMinutes(5));
    } catch (Exception e) {
      log.warn("工具缓存写入失败，不影响执行结果: reason={}", e.getMessage());
    }
  }

  private void recordSuccessQuietly(String toolName) {
    try {
      circuitBreaker.recordSuccess(toolName);
    } catch (Exception e) {
      log.warn("工具熔断成功状态写入失败: tool={}, reason={}", toolName, e.getMessage());
    }
  }

  private void recordFailureQuietly(String toolName) {
    try {
      circuitBreaker.recordFailure(toolName);
    } catch (Exception e) {
      log.warn("工具熔断失败状态写入失败: tool={}, reason={}", toolName, e.getMessage());
    }
  }
}
