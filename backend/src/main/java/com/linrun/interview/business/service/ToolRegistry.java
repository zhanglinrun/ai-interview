package com.linrun.interview.business.service;

import com.linrun.interview.business.vo.InterviewEvidence.Bundle;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.service.KnowledgeBaseListService;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.linrun.interview.rag.service.RagTraceRecorder;

/** Server-owned registry. LLM input cannot register or enable tools. */
@Component
@RequiredArgsConstructor
public class ToolRegistry {
  private final ResumeReadTool resumeReadTool;
  private final InterviewKnowledgeRetrievalService retrievalService;
  private final KnowledgeBaseListService knowledgeBaseListService;
  private final RagTraceRecorder ragTraceRecorder;
  private final Map<String, RegisteredTool> tools = new ConcurrentHashMap<>();

  @PostConstruct
  void registerBuiltIns() {
    register(new ToolDescriptor("resume.read", Set.of("INTERVIEWER"), false, true,
        2_000L, 1_000, "1"), (context, input) -> {
          Long resumeId = longValue(input.get("resumeId"));
          if (context == null || context.userId() == null || resumeId == null) {
            return ToolResult.empty(null, "本次面试没有可读取的归属简历");
          }
          return resumeReadTool.readForGateway(context.userId(), resumeId);
        });

    register(new ToolDescriptor("evidence.search", Set.of("ORCHESTRATOR"), true, true,
        4_000L, 4_000, "1"), (context, input) -> {
          String query = String.valueOf(input.getOrDefault("query", ""));
          List<Long> ids = longList(input.get("knowledgeBaseIds"));
          if (context == null || context.userId() == null || query.isBlank() || ids.isEmpty()) {
            return ToolResult.empty(Bundle.empty(query), "没有可检索的知识库或查询");
          }
          InterviewKnowledgeRetrievalService.RetrievalResult result =
              retrievalService.retrieveEvidenceResult(context.userId(), ids, query);
          Bundle bundle = result.bundle();
          String ragRunId = ragTraceRecorder.recordInterviewEvidence(
              context.traceId(), context.userId(), context.sessionId(), context.agentRunId(),
              query, bundle, result.failed(), 0L);
          ToolResult<Bundle> toolResult = result.failed()
              ? ToolResult.degraded(bundle, result.failureReason(), "证据检索失败，已返回空证据包")
              : bundle.promptEvidence().isEmpty()
              ? ToolResult.empty(bundle, "未检索到可用证据")
              : ToolResult.success(bundle, "evidence=" + bundle.promptEvidence().size());
          return toolResult.withRagRunId(ragRunId);
        });
  }

  public void register(ToolDescriptor descriptor, ToolHandler handler) {
    if (descriptor == null || descriptor.name() == null || handler == null) {
      throw new IllegalArgumentException("工具描述和处理器不能为空");
    }
    if (tools.putIfAbsent(descriptor.name(), new RegisteredTool(descriptor, handler)) != null) {
      throw new IllegalStateException("重复注册工具: " + descriptor.name());
    }
  }

  public RegisteredTool get(String name) {
    return tools.get(name);
  }

  /**
   * Returns a resource-version discriminator for cacheable tools.  It is
   * deliberately computed with the explicit user id rather than UserContext,
   * because ToolExecutor may run the handler on a worker thread.
   */
  public String cacheDiscriminator(String toolName, ToolExecutionContext context,
                                   Map<String, Object> input) {
    if (!"evidence.search".equals(toolName) || context == null || context.userId() == null) {
      return "";
    }
    List<Long> ids = longList(input == null ? null : input.get("knowledgeBaseIds"));
    if (ids.isEmpty()) {
      return "kb:none";
    }
    try {
      return knowledgeBaseListService.listReadableByIds(context.userId(), ids).stream()
          .sorted(Comparator.comparing(KnowledgeBaseEntity::getId))
          .map(kb -> String.valueOf(kb.getId()) + "@"
              + String.valueOf(kb.getCurrentVersionId()) + "@owner="
              + String.valueOf(kb.getUserId()) + "@access="
              + String.valueOf(kb.getAccessibleBy()))
          .reduce((left, right) -> left + "," + right)
          .orElse("kb:none");
    } catch (Exception e) {
      // A failed discriminator must never grant a stale cache hit.  Returning
      // a request-specific marker makes this call miss and lets the handler
      // produce the structured degraded result.
      return "kb:version-unavailable:" + System.nanoTime();
    }
  }

  public List<ToolDescriptor> descriptors() {
    return tools.values().stream().map(RegisteredTool::descriptor).sorted(
        java.util.Comparator.comparing(ToolDescriptor::name)).toList();
  }

  private static Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return value == null ? null : Long.valueOf(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static List<Long> longList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().map(ToolRegistry::longValue).filter(java.util.Objects::nonNull).toList();
  }

  public record RegisteredTool(ToolDescriptor descriptor, ToolHandler handler) {
  }
}
