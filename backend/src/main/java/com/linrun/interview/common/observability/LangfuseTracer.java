package com.linrun.interview.common.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Langfuse 全链路 tracer（P5）——自研 REST ingestion 客户端。
 *
 * <p>Java 侧无官方 SDK，本类封装 Langfuse batch ingestion（{@code POST /api/public/ingestion}）：
 * <ul>
 *   <li><b>异步旁路</b>：埋点只把事件入内存队列，{@link #flush()} 由 {@code @Scheduled} 批量 POST，
 *       业务线程零阻塞；</li>
 *   <li><b>失败降级</b>：上报异常只 debug 日志、丢弃事件，绝不影响主链路；</li>
 *   <li><b>懒建 trace</b>：首个 span/generation 才发 trace-create，避免空 trace；</li>
 *   <li><b>父子链接</b>：span 经 {@link LangfuseContext} 观测栈自动挂到当前活跃 span 下，
 *       形成 Orchestrator → Planner/Interviewer/Critic(LLM) 的 span 树。</li>
 * </ul>
 *
 * <p>关闭（{@code app.observability.langfuse.enabled=false}）或缺 key 时全链路 no-op。
 */
@Slf4j
@Component
public class LangfuseTracer {

  private static final String INGESTION_PATH = "/api/public/ingestion";

  private final LangfuseProperties props;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
  private final String authHeader;
  private final boolean configured;

  public LangfuseTracer(LangfuseProperties props, ObjectMapper objectMapper) {
    this.props = props;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    boolean hasKeys = props.getPublicKey() != null && !props.getPublicKey().isBlank()
        && props.getSecretKey() != null && !props.getSecretKey().isBlank();
    this.configured = props.isEnabled() && hasKeys;
    this.authHeader = hasKeys
        ? "Basic " + Base64.getEncoder().encodeToString(
            (props.getPublicKey() + ":" + props.getSecretKey()).getBytes(StandardCharsets.UTF_8))
        : null;
    if (props.isEnabled() && !hasKeys) {
      log.warn("[Langfuse] enabled=true 但缺少 public-key/secret-key，观测上报已禁用");
    }
  }

  public boolean isEnabled() {
    return configured;
  }

  // ==================== trace 生命周期 ====================

  /**
   * 显式开启一个 trace（业务入口调用，携带完整 name/user/session/input）。幂等：一个线程只创建一次。
   *
   * @return traceId（关闭时返回 null）
   */
  public String startTrace(String name, Long userId, String sessionId, Object input) {
    if (!configured) {
      return null;
    }
    String traceId = ensureTraceId();
    if (!LangfuseContext.isTraceEmitted()) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("id", traceId);
      body.put("name", name);
      body.put("timestamp", Instant.now().toString());
      body.put("environment", props.getEnvironment());
      if (userId != null) {
        body.put("userId", String.valueOf(userId));
      }
      if (sessionId != null) {
        body.put("sessionId", sessionId);
      }
      if (input != null) {
        body.put("input", serialize(input));
      }
      enqueue("trace-create", body);
      LangfuseContext.markTraceEmitted();
    }
    return traceId;
  }

  /** 补一条 trace 输出（可选，trace 结束时调用；靠同 id upsert 合并）。 */
  public void updateTraceOutput(Object output) {
    if (!configured || !LangfuseContext.hasTrace() || output == null) {
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", LangfuseContext.getTraceId());
    body.put("output", serialize(output));
    enqueue("trace-create", body);
  }

  private String ensureTraceId() {
    String traceId = LangfuseContext.getTraceId();
    if (traceId == null) {
      traceId = UUID.randomUUID().toString();
      LangfuseContext.setTraceId(traceId);
    }
    return traceId;
  }

  private void ensureTraceEmitted(String fallbackName) {
    if (!LangfuseContext.isTraceEmitted()) {
      startTrace(fallbackName, null, null, null);
    }
  }

  // ==================== span / generation ====================

  /** 开启一个普通 span（如 planner/interviewer/critic/rag-retrieve 阶段）。 */
  public LangfuseSpan span(String name, Object input) {
    return observation(false, name, null, input);
  }

  /** 开启一个 generation span（LLM 调用；一般由 {@link LangfuseChatModelListener} 自动调用）。 */
  public LangfuseSpan generation(String name, String model, Object input) {
    return observation(true, name, model, input);
  }

  private LangfuseSpan observation(boolean generation, String name, String model, Object input) {
    if (!configured) {
      return LangfuseSpan.NOOP;
    }
    ensureTraceEmitted(name);
    String traceId = LangfuseContext.getTraceId();
    if (traceId == null) {
      return LangfuseSpan.NOOP;
    }
    String id = UUID.randomUUID().toString();
    String parentId = LangfuseContext.currentParentId();
    // generation 是叶子（LLM 调用下不嵌套子 span），不入栈——避免流式回调跨线程 pop 破坏父子栈
    if (!generation) {
      LangfuseContext.pushObservation(id);
    }
    return new LangfuseSpan(id, traceId, parentId, generation, name, model,
        input == null ? null : serialize(input));
  }

  /** 结束一个 span/generation 并上报（成功）。 */
  public void end(LangfuseSpan span, Object output) {
    endInternal(span, output, null, null, null, null);
  }

  /** 结束一个 generation 并附带 token 用量。 */
  public void endGeneration(LangfuseSpan span, Object output,
                            Integer inputTokens, Integer outputTokens, Integer totalTokens) {
    endInternal(span, output, inputTokens, outputTokens, totalTokens, null);
  }

  /** 以错误结束一个 span/generation。 */
  public void endError(LangfuseSpan span, Throwable error) {
    String msg = error == null ? "unknown error" : error.toString();
    endInternal(span, null, null, null, null, msg);
  }

  private void endInternal(LangfuseSpan span, Object output, Integer inputTokens,
                           Integer outputTokens, Integer totalTokens, String errorMessage) {
    if (span == null || span.isNoop() || !configured) {
      return;
    }
    if (!span.generation) {
      LangfuseContext.popObservation();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", span.id);
    body.put("traceId", span.traceId);
    if (span.parentId != null) {
      body.put("parentObservationId", span.parentId);
    }
    body.put("name", span.name);
    body.put("startTime", span.startTime.toString());
    body.put("endTime", Instant.now().toString());
    body.put("environment", props.getEnvironment());
    if (span.inputJson != null) {
      body.put("input", span.inputJson);
    }
    if (output != null) {
      body.put("output", serialize(output));
    }
    if (errorMessage != null) {
      body.put("level", "ERROR");
      body.put("statusMessage", truncate(errorMessage));
    }
    if (span.generation) {
      if (span.model != null) {
        body.put("model", span.model);
      }
      if (inputTokens != null || outputTokens != null || totalTokens != null) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input", inputTokens);
        usage.put("output", outputTokens);
        usage.put("total", totalTokens);
        usage.put("unit", "TOKENS");
        body.put("usage", usage);
      }
    }
    enqueue(span.generation ? "generation-create" : "span-create", body);
  }

  // ==================== trace 跳转 URL ====================

  public String traceUrl(String traceId) {
    if (traceId == null) {
      return null;
    }
    return props.getTraceUrlTemplate()
        .replace("{baseUrl}", stripTrailingSlash(props.getBaseUrl()))
        .replace("{traceId}", traceId);
  }

  // ==================== flush ====================

  /** 定时批量上报；只在调度线程运行，业务线程不受阻。 */
  @Scheduled(fixedDelayString = "${app.observability.langfuse.flush-interval-ms:2000}")
  public void flush() {
    if (!configured || queue.isEmpty()) {
      return;
    }
    List<Map<String, Object>> batch = new ArrayList<>();
    queue.drainTo(batch, props.getBatchSize());
    if (batch.isEmpty()) {
      return;
    }
    try {
      String json = objectMapper.writeValueAsString(Map.of("batch", batch));
      HttpRequest request = HttpRequest.newBuilder(
              URI.create(stripTrailingSlash(props.getBaseUrl()) + INGESTION_PATH))
          .version(HttpClient.Version.HTTP_1_1)
          .header("Content-Type", "application/json")
          .header("Authorization", authHeader)
          .timeout(Duration.ofSeconds(10))
          .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        log.warn("[Langfuse] ingestion 非 2xx: status={}, body={}",
            response.statusCode(), truncate(response.body()));
      }
    } catch (Exception e) {
      log.warn("[Langfuse] 上报失败(忽略), 丢弃 {} 条事件: {}", batch.size(), e.toString());
    }
  }

  // ==================== 内部工具 ====================

  private void enqueue(String type, Map<String, Object> body) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("id", UUID.randomUUID().toString());
    event.put("type", type);
    event.put("timestamp", Instant.now().toString());
    event.put("body", body);
    while (queue.size() >= props.getMaxQueue()) {
      queue.poll();
    }
    queue.offer(event);
  }

  private String serialize(Object value) {
    if (value == null) {
      return null;
    }
    String text;
    if (value instanceof String s) {
      text = s;
    } else {
      try {
        text = objectMapper.writeValueAsString(value);
      } catch (Exception e) {
        text = String.valueOf(value);
      }
    }
    return truncate(text);
  }

  private String truncate(String text) {
    if (text == null) {
      return null;
    }
    int max = Math.max(200, props.getMaxFieldChars());
    return text.length() <= max ? text : text.substring(0, max) + "…(truncated)";
  }

  private static String stripTrailingSlash(String url) {
    if (url == null || url.isEmpty()) {
      return "";
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
