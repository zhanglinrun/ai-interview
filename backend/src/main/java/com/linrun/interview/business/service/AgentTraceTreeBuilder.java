package com.linrun.interview.business.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.entity.AgentRunStepEntity;
import com.linrun.interview.business.vo.AgentTraceSpanDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把平铺的 agent_steps 收成可讲的 trace 树。
 *
 * <p>写入时 Planner 与首题曾共用一个 rootSpanId，chat 与编排日记又各写一遍，
 * 按 parent 原样展开会得到「Chat · Planner / plan / Chat · Interviewer 挤在一堆」。
 * 回放按阶段重收：定大纲、第 N 题；工具仍挂在触发它的 Chat 下。
 */
public final class AgentTraceTreeBuilder {

  private static final Set<String> ALWAYS_HIDDEN = Set.of("state");
  private static final Set<String> HIDDEN_IF_CHAT = Set.of(
      "plan", "plan_fallback", "ask", "ask_failed", "critique");

  private final ObjectMapper objectMapper;

  public AgentTraceTreeBuilder(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
  }

  public List<AgentTraceSpanDTO> build(List<AgentRunStepEntity> steps) {
    if (steps == null || steps.isEmpty()) {
      return List.of();
    }
    List<AgentTraceSpanDTO> nodes = new ArrayList<>();
    Map<String, AgentTraceSpanDTO> byId = new LinkedHashMap<>();
    Map<String, List<AgentTraceSpanDTO>> toolsByParent = new LinkedHashMap<>();
    int index = 0;
    for (AgentRunStepEntity step : steps) {
      AgentTraceSpanDTO node = toNode(step, index++);
      byId.put(node.spanId(), node);
      if (AgentSpanMetadata.KIND_TOOL.equals(node.kind())
          && node.parentSpanId() != null && !node.parentSpanId().isBlank()) {
        toolsByParent.computeIfAbsent(node.parentSpanId(), key -> new ArrayList<>()).add(node);
      } else {
        nodes.add(node);
      }
    }
    List<AgentTraceSpanDTO> withTools = nodes.stream()
        .map(node -> attachTools(node, toolsByParent))
        .toList();
    List<List<AgentTraceSpanDTO>> phases = splitPhases(withTools);
    List<AgentTraceSpanDTO> forest = new ArrayList<>();
    int questionSeq = 0;
    for (int i = 0; i < phases.size(); i++) {
      List<AgentTraceSpanDTO> phase = phases.get(i);
      boolean planning = isPlanningPhase(phase);
      boolean evaluating = isEvaluatingPhase(phase);
      if (!planning && !evaluating) {
        questionSeq++;
      }
      List<AgentTraceSpanDTO> visible = visibleChildren(phase);
      if (visible.isEmpty()) {
        continue;
      }
      String title = planning ? "定大纲"
          : evaluating ? "评估"
          : "第 " + questionNumber(phase, questionSeq) + " 题";
      String action = planning ? "planning" : evaluating ? "evaluating" : "question";
      forest.add(new AgentTraceSpanDTO(
          "phase-" + i + "-" + action,
          null,
          AgentSpanMetadata.KIND_AGENT,
          "orchestrator",
          action,
          title,
          null,
          null,
          "COMPLETED",
          sumLatency(visible),
          null,
          null,
          null,
          questionIndexOf(phase),
          visible));
    }
    return forest;
  }

  private AgentTraceSpanDTO attachTools(AgentTraceSpanDTO node,
                                        Map<String, List<AgentTraceSpanDTO>> toolsByParent) {
    List<AgentTraceSpanDTO> tools = toolsByParent.getOrDefault(node.spanId(), List.of());
    return tools.isEmpty() ? node : node.withChildren(tools);
  }

  private List<List<AgentTraceSpanDTO>> splitPhases(List<AgentTraceSpanDTO> nodes) {
    List<List<AgentTraceSpanDTO>> phases = new ArrayList<>();
    List<AgentTraceSpanDTO> current = new ArrayList<>();
    for (AgentTraceSpanDTO node : nodes) {
      if (!current.isEmpty() && startsNewPhase(current, node)) {
        phases.add(current);
        current = new ArrayList<>();
      }
      current.add(node);
    }
    if (!current.isEmpty()) {
      phases.add(current);
    }
    return phases;
  }

  private boolean startsNewPhase(List<AgentTraceSpanDTO> current, AgentTraceSpanDTO next) {
    if (isEvaluatingStep(next) != isEvaluatingPhase(current)
        && (isEvaluatingStep(next) || isEvaluatingPhase(current))) {
      return true;
    }
    if (isPlanningPhase(current) && (isQuestionStep(next) || isEvaluatingStep(next))) {
      return true;
    }
    if (isEvaluatingPhase(current) && !isEvaluatingStep(next)) {
      return true;
    }
    Integer currentIndex = questionIndexOf(current);
    Integer nextIndex = next.questionIndex();
    if (currentIndex != null && nextIndex != null && !currentIndex.equals(nextIndex)) {
      return true;
    }
    return nextIndex == null && currentIndex == null
        && AgentSpanMetadata.KIND_CHAT.equals(next.kind())
        && isQuestionStep(next)
        && current.stream().anyMatch(step -> "finish".equals(step.action()));
  }

  private boolean isPlanningPhase(List<AgentTraceSpanDTO> phase) {
    return !phase.isEmpty() && phase.stream().allMatch(this::isPlanningStep);
  }

  private boolean isPlanningStep(AgentTraceSpanDTO node) {
    String action = safe(node.action());
    String role = safe(node.role());
    return "plan".equals(action) || "plan_fallback".equals(action)
        || "planner".equals(role);
  }

  private boolean isEvaluatingPhase(List<AgentTraceSpanDTO> phase) {
    return !phase.isEmpty() && phase.stream().allMatch(this::isEvaluatingStep);
  }

  private boolean isEvaluatingStep(AgentTraceSpanDTO node) {
    String action = safe(node.action());
    String role = safe(node.role());
    return "enqueue_evaluation".equals(action)
        || "evaluate_completed".equals(action)
        || "evaluate_failed".equals(action)
        || "evaluator".equals(role);
  }

  private boolean isQuestionStep(AgentTraceSpanDTO node) {
    String action = safe(node.action());
    String role = safe(node.role());
    return "turn_decision".equals(action)
        || "ask".equals(action)
        || "ask_failed".equals(action)
        || "critique".equals(action)
        || "finish".equals(action)
        || "grounding_reject".equals(action)
        || "reflexion_limit".equals(action)
        || "interviewer".equals(role)
        || "critic".equals(role);
  }

  private List<AgentTraceSpanDTO> visibleChildren(List<AgentTraceSpanDTO> phase) {
    boolean plannerChat = hasChat(phase, "planner");
    boolean interviewerChat = hasChat(phase, "interviewer");
    boolean criticChat = hasChat(phase, "critic");
    List<AgentTraceSpanDTO> kept = new ArrayList<>();
    for (AgentTraceSpanDTO node : phase) {
      String action = safe(node.action());
      if (ALWAYS_HIDDEN.contains(action)) {
        continue;
      }
      if (HIDDEN_IF_CHAT.contains(action)
          && ((plannerChat && (action.startsWith("plan")))
          || (interviewerChat && action.startsWith("ask"))
          || (criticChat && "critique".equals(action)))) {
        continue;
      }
      kept.add(node);
    }
    if (isEvaluatingPhase(phase)) {
      return orderEvaluatingPhase(kept);
    }
    return orderQuestionPhase(kept);
  }

  private List<AgentTraceSpanDTO> orderEvaluatingPhase(List<AgentTraceSpanDTO> nodes) {
    List<AgentTraceSpanDTO> ordered = new ArrayList<>();
    nodes.stream().filter(node -> "enqueue_evaluation".equals(node.action())).forEach(ordered::add);
    nodes.stream().filter(node -> AgentSpanMetadata.KIND_CHAT.equals(node.kind())).forEach(ordered::add);
    nodes.stream().filter(node -> "evaluate_completed".equals(node.action())
            || "evaluate_failed".equals(node.action()))
        .forEach(ordered::add);
    nodes.stream().filter(node -> !ordered.contains(node)).forEach(ordered::add);
    return ordered;
  }

  private List<AgentTraceSpanDTO> orderQuestionPhase(List<AgentTraceSpanDTO> nodes) {
    if (nodes.stream().noneMatch(this::isQuestionStep)) {
      return nodes;
    }
    List<AgentTraceSpanDTO> ordered = new ArrayList<>();
    nodes.stream().filter(node -> "turn_decision".equals(node.action())).forEach(ordered::add);
    nodes.stream().filter(node -> "interviewer".equalsIgnoreCase(node.role())
            && AgentSpanMetadata.KIND_CHAT.equals(node.kind()))
        .forEach(ordered::add);
    nodes.stream().filter(node -> "grounding_reject".equals(node.action())
            || "reflexion_limit".equals(node.action()))
        .forEach(ordered::add);
    nodes.stream().filter(node -> "critic".equalsIgnoreCase(node.role())
            && AgentSpanMetadata.KIND_CHAT.equals(node.kind()))
        .forEach(ordered::add);
    nodes.stream().filter(node -> "finish".equals(node.action())).forEach(ordered::add);
    nodes.stream().filter(node -> !ordered.contains(node)).forEach(ordered::add);
    return ordered;
  }

  private boolean hasChat(List<AgentTraceSpanDTO> phase, String role) {
    return phase.stream().anyMatch(node ->
        AgentSpanMetadata.KIND_CHAT.equals(node.kind()) && role.equalsIgnoreCase(node.role()));
  }

  private int questionNumber(List<AgentTraceSpanDTO> phase, int fallback) {
    Integer index = questionIndexOf(phase);
    return index == null ? fallback : index + 1;
  }

  private Integer questionIndexOf(List<AgentTraceSpanDTO> phase) {
    return phase.stream()
        .map(AgentTraceSpanDTO::questionIndex)
        .filter(index -> index != null)
        .findFirst()
        .orElse(null);
  }

  private Long sumLatency(List<AgentTraceSpanDTO> nodes) {
    long total = 0L;
    boolean any = false;
    for (AgentTraceSpanDTO node : nodes) {
      if (node.latencyMs() != null) {
        total += node.latencyMs();
        any = true;
      }
      Long child = sumLatency(node.children());
      if (child != null) {
        total += child;
        any = true;
      }
    }
    return any ? total : null;
  }

  private AgentTraceSpanDTO toNode(AgentRunStepEntity step, int index) {
    String spanId = step.getSpanId() == null || step.getSpanId().isBlank()
        ? "step-" + (step.getId() == null ? index : step.getId())
        : step.getSpanId();
    JsonNode metadata = readJson(step.getMetadataJson());
    String kind = AgentSpanMetadata.kindOf(metadata, step.getAction(), step.getRole());
    return new AgentTraceSpanDTO(
        spanId,
        blankToNull(step.getParentSpanId()),
        kind,
        step.getRole(),
        step.getAction(),
        title(kind, step.getRole(), step.getAction()),
        step.getActionInput(),
        step.getObservation(),
        step.getStatus() == null ? "COMPLETED" : step.getStatus(),
        step.getLatencyMs(),
        AgentSpanMetadata.text(metadata, "model"),
        AgentSpanMetadata.integer(metadata, "inputTokens"),
        AgentSpanMetadata.integer(metadata, "outputTokens"),
        step.getQuestionIndex(),
        List.of());
  }

  private String title(String kind, String role, String action) {
    String safeRole = role == null || role.isBlank() ? "agent" : role;
    String safeAction = action == null || action.isBlank() ? "step" : action;
    if (AgentSpanMetadata.KIND_CHAT.equals(kind)) {
      return "Chat · " + capitalize(safeRole);
    }
    if (AgentSpanMetadata.KIND_TOOL.equals(kind)) {
      return "Tool · " + safeAction;
    }
    return switch (safeAction) {
      case "turn_decision" -> "逐轮决策";
      case "finish" -> "本轮定题";
      case "grounding_reject" -> "接地打回";
      case "reflexion_limit" -> "Reflexion 达上限";
      case "plan", "plan_fallback" -> "大纲";
      case "enqueue_evaluation" -> "评估入队";
      case "evaluate_completed" -> "评估完成";
      case "evaluate_failed" -> "评估失败";
      default -> safeAction;
    };
  }

  private String capitalize(String value) {
    if (value.isEmpty()) {
      return value;
    }
    return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
  }

  private JsonNode readJson(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readTree(raw);
    } catch (Exception e) {
      return null;
    }
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private String safe(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }
}
