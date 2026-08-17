package com.linrun.interview.business.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.entity.AgentRunStepEntity;
import com.linrun.interview.business.vo.AgentTraceActDTO;
import com.linrun.interview.business.vo.AgentTraceEventDTO;
import com.linrun.interview.business.vo.AgentTracePlaybackDTO;
import com.linrun.interview.business.vo.InterviewPlan;
import com.linrun.interview.business.vo.InterviewPlan.PlanTopic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把 agent_steps 的 role/action/JSON 解成回放页能直接读的决策事件。
 */
public final class AgentTraceInterpreter {

  private static final Set<String> KNOWN_ACTIONS = Set.of(
      "plan", "plan_fallback", "turn_decision", "state", "ask", "ask_failed",
      "grounding_reject", "critique", "reflexion_limit", "finish",
      "enqueue_evaluation", "evaluate_completed", "evaluate_failed", "command", "chat",
      "load_materials", "map_capabilities", "retrieve_evidence",
      "select_questions", "freeze_questions");

  private final ObjectMapper objectMapper;

  public AgentTraceInterpreter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
  }

  public AgentTracePlaybackDTO interpret(String sessionId,
                                         List<String> sourceIds,
                                         List<AgentRunStepEntity> steps,
                                         PlaybackContext context) {
    PlaybackContext safe = context == null ? PlaybackContext.empty() : context;
    List<AgentRunStepEntity> safeSteps = steps == null ? List.of() : steps;
    List<AgentTraceActDTO> acts = groupActs(safeSteps);
    InterviewPlan plan = safe.plan() != null ? safe.plan() : extractPlan(safeSteps);
    int reflexionRounds = acts.stream().mapToInt(AgentTraceActDTO::reflexionRounds).sum();
    int criticRejects = (int) safeSteps.stream().filter(this::isCriticReject).count();
    int groundingRejects = (int) safeSteps.stream()
        .filter(step -> "grounding_reject".equalsIgnoreCase(step.getAction()))
        .count();
    int toolCalls = (int) safeSteps.stream().filter(this::isToolStep).count();
    String emptyReason = safeSteps.isEmpty() ? resolveEmptyReason(safe, plan) : null;
    return new AgentTracePlaybackDTO(
        sessionId,
        sourceIds == null ? List.of() : List.copyOf(sourceIds),
        safe.agentMode() || plan != null,
        safeSteps.size(),
        reflexionRounds,
        criticRejects,
        groundingRejects,
        toolCalls,
        emptyReason,
        emptyHint(emptyReason),
        plan,
        acts);
  }

  public String resolveState(String role, String action, String actionInput) {
    String safeAction = action == null ? "" : action.toLowerCase(Locale.ROOT);
    String safeRole = role == null ? "" : role.toLowerCase(Locale.ROOT);
    if ("state".equals(safeAction) && actionInput != null && !actionInput.isBlank()) {
      return actionInput.trim().toUpperCase(Locale.ROOT);
    }
    if (safeAction.contains("reflexion")
        || (actionInput != null && actionInput.toLowerCase(Locale.ROOT).contains("retryhint"))) {
      return "REFLEXION";
    }
    if ("enqueue_evaluation".equals(safeAction) || "evaluate_completed".equals(safeAction)
        || "evaluate_failed".equals(safeAction) || "evaluator".equals(safeRole)) {
      return "EVALUATING";
    }
    if ("plan".equals(safeAction) || "plan_fallback".equals(safeAction) || "planner".equals(safeRole)) {
      return "PLANNING";
    }
    if ("critique".equals(safeAction) || "critic".equals(safeRole)) {
      return "CRITIQUING";
    }
    if ("enqueue_evaluation".equals(safeAction) || "evaluator".equals(safeRole)) {
      return "EVALUATING";
    }
    if ("finish".equals(safeAction) || "command".equals(safeAction)) {
      return "READY";
    }
    if ("ask".equals(safeAction) || "ask_failed".equals(safeAction)
        || "grounding_reject".equals(safeAction) || "interviewer".equals(safeRole)
        || "turn_decision".equals(safeAction)) {
      return "ASKING";
    }
    return safeRole.isBlank() ? "ORCHESTRATOR" : safeRole.toUpperCase(Locale.ROOT);
  }

  private static final Integer EVALUATING_ACT_KEY = Integer.MIN_VALUE;

  private List<AgentTraceActDTO> groupActs(List<AgentRunStepEntity> steps) {
    Map<Integer, List<AgentTraceEventDTO>> grouped = new LinkedHashMap<>();
    for (AgentRunStepEntity step : steps) {
      Integer key = isEvaluatingAction(step.getAction(), step.getRole())
          ? EVALUATING_ACT_KEY : step.getQuestionIndex();
      grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
          .add(toEvent(step));
    }
    List<AgentTraceActDTO> acts = new ArrayList<>();
    for (Map.Entry<Integer, List<AgentTraceEventDTO>> entry : grouped.entrySet()) {
      acts.add(toAct(entry.getKey(), entry.getValue()));
    }
    return acts;
  }

  private AgentTraceActDTO toAct(Integer questionIndex, List<AgentTraceEventDTO> events) {
    List<String> statePath = new ArrayList<>();
    for (AgentTraceEventDTO event : events) {
      if (event.state() == null || event.state().isBlank()) {
        continue;
      }
      if (statePath.isEmpty() || !statePath.get(statePath.size() - 1).equals(event.state())) {
        statePath.add(event.state());
      }
    }
    int reflexionRounds = (int) events.stream().filter(AgentTraceEventDTO::reflexion).count();
    String finalQuestion = lastQuestion(events);
    String followUpAction = lastNonBlank(events, AgentTraceEventDTO::followUpAction);
    Boolean criticApproved = null;
    for (int i = events.size() - 1; i >= 0; i--) {
      if (events.get(i).approved() != null && "critique".equalsIgnoreCase(events.get(i).action())) {
        criticApproved = events.get(i).approved();
        break;
      }
      if (events.get(i).approved() != null && "finish".equalsIgnoreCase(events.get(i).action())) {
        criticApproved = events.get(i).approved();
        break;
      }
    }
    String title = questionIndex == null
        ? "PLANNING 大纲"
        : EVALUATING_ACT_KEY.equals(questionIndex)
            ? "评估"
            : "第 " + (questionIndex + 1) + " 题";
    if (followUpAction != null && !followUpAction.isBlank() && questionIndex != null) {
      title = title + " · " + followUpAction;
    }
    return new AgentTraceActDTO(
        questionIndex, title, statePath, reflexionRounds, finalQuestion,
        followUpAction, criticApproved, events);
  }

  private AgentTraceEventDTO toEvent(AgentRunStepEntity step) {
    String action = step.getAction() == null ? "" : step.getAction();
    String input = nullToEmpty(step.getActionInput());
    String observation = nullToEmpty(step.getObservation());
    String state = resolveState(step.getRole(), action, input);
    JsonNode inputJson = readJson(input);
    JsonNode observationJson = readJson(observation);
    String headline;
    String body;
    Boolean approved = null;
    Integer score = null;
    String retryHint = null;
    String followUpAction = null;
    String capability = null;
    List<String> evidenceIds = List.of();
    boolean reflexion = "REFLEXION".equals(state) || "reflexion_limit".equalsIgnoreCase(action);

    switch (action.toLowerCase(Locale.ROOT)) {
      case "plan", "plan_fallback" -> {
        boolean fallback = "plan_fallback".equalsIgnoreCase(action);
        InterviewPlan plan = readPlan(observationJson, observation);
        headline = fallback ? "Planner 降级大纲" : "Planner 产出大纲";
        body = plan == null ? firstNonBlank(observation, input) : summarizePlan(plan);
      }
      case "turn_decision" -> {
        followUpAction = text(observationJson, "action");
        capability = capabilityLabel(observationJson.get("targetCapability"));
        headline = "TurnDecision · " + (followUpAction.isBlank() ? "未命名动作" : followUpAction)
            + (capability.isBlank() ? "" : " · " + capability);
        body = firstNonBlank(text(observationJson, "rationale"), observation);
        evidenceIds = stringList(observationJson, "promptEvidenceIds");
        if (evidenceIds.isEmpty()) {
          evidenceIds = stringList(observationJson, "candidateEvidenceIds");
        }
      }
      case "state" -> {
        headline = "状态 → " + state;
        body = firstNonBlank(observation, input);
        reflexion = "REFLEXION".equals(state);
      }
      case "ask" -> {
        retryHint = extractRetryHint(input);
        reflexion = retryHint != null;
        headline = reflexion ? "Interviewer 按 retryHint 重出题" : "Interviewer 出题";
        body = firstNonBlank(observation, input);
      }
      case "ask_failed" -> {
        headline = "Interviewer 出题失败";
        body = firstNonBlank(observation, "将走兜底题");
      }
      case "grounding_reject" -> {
        approved = false;
        retryHint = firstNonBlank(observation, input);
        reflexion = true;
        headline = "Grounding 打回";
        body = "题面：" + firstNonBlank(input, "（空）") + "\n原因：" + firstNonBlank(observation, "未通过证据/简历专名校验");
      }
      case "critique" -> {
        approved = bool(observationJson, "approved");
        score = integer(observationJson, "score");
        retryHint = text(observationJson, "retryHint");
        String feedback = text(observationJson, "feedback");
        reflexion = Boolean.FALSE.equals(approved);
        headline = Boolean.FALSE.equals(approved)
            ? "Critic 打回" + (score == null ? "" : " · " + score)
            : "Critic 通过" + (score == null ? "" : " · " + score);
        body = firstNonBlank(feedback, observation);
      }
      case "reflexion_limit" -> {
        reflexion = true;
        headline = "Reflexion 达上限，采用最后一版";
        body = firstNonBlank(observation, "round=" + input);
      }
      case "finish" -> {
        followUpAction = text(observationJson, "followUpAction");
        capability = text(observationJson, "capabilityAtomId");
        evidenceIds = stringList(observationJson, "selectedEvidenceIds");
        approved = bool(observationJson, "criticApproved");
        headline = "本轮定题"
            + (followUpAction.isBlank() ? "" : " · " + followUpAction)
            + (Boolean.FALSE.equals(approved) ? "（Critic 未通过，已短路）" : "");
        body = firstNonBlank(text(observationJson, "question"), observation);
      }
      case "enqueue_evaluation" -> {
        headline = "评估入队";
        body = firstNonBlank(observation, "委托统一评估管线");
      }
      case "evaluate_completed" -> {
        headline = "评估完成";
        body = firstNonBlank(observation, "评估完成");
      }
      case "evaluate_failed" -> {
        headline = "评估失败";
        body = firstNonBlank(observation, "评估失败");
      }
      case "command" -> {
        headline = "面试命令";
        body = firstNonBlank(observation, input);
      }
      case "chat" -> {
        headline = "Chat · " + (step.getRole() == null ? "LLM" : step.getRole());
        body = firstNonBlank(observation, input);
      }
      case "load_materials" -> {
        headline = "读取岗位与材料";
        body = firstNonBlank(observation, input);
      }
      case "map_capabilities" -> {
        headline = "对齐岗位能力";
        body = firstNonBlank(observation, input);
      }
      case "retrieve_evidence" -> {
        headline = "检索并冻结证据";
        body = firstNonBlank(observation, input);
        evidenceIds = stringList(observationJson, "evidenceIds");
      }
      case "select_questions" -> {
        headline = "按模板选定题目";
        body = firstNonBlank(observation, input);
      }
      case "freeze_questions" -> {
        headline = "冻结题单";
        body = firstNonBlank(observation, input);
      }
      default -> {
        boolean tool = isToolStep(step);
        headline = tool ? "Tool · " + action : action.isBlank() ? "步骤" : action;
        body = firstNonBlank(observation, input);
      }
    }

    return new AgentTraceEventDTO(
        step.getStepOrder() == null ? 0 : step.getStepOrder(),
        step.getQuestionIndex(),
        step.getRole(),
        action,
        state,
        headline,
        body,
        approved,
        score,
        blankToNull(retryHint),
        blankToNull(followUpAction),
        blankToNull(capability),
        evidenceIds,
        reflexion,
        blankToNull(input));
  }

  private boolean isCriticReject(AgentRunStepEntity step) {
    if (!"critique".equalsIgnoreCase(step.getAction())) {
      return false;
    }
    Boolean approved = bool(readJson(step.getObservation()), "approved");
    return Boolean.FALSE.equals(approved);
  }

  private boolean isEvaluatingAction(String action, String role) {
    String safeAction = action == null ? "" : action.toLowerCase(Locale.ROOT);
    String safeRole = role == null ? "" : role.toLowerCase(Locale.ROOT);
    return "enqueue_evaluation".equals(safeAction)
        || "evaluate_completed".equals(safeAction)
        || "evaluate_failed".equals(safeAction)
        || "evaluator".equals(safeRole);
  }

  private boolean isToolStep(AgentRunStepEntity step) {
    String action = step.getAction() == null ? "" : step.getAction().toLowerCase(Locale.ROOT);
    if (KNOWN_ACTIONS.contains(action) || "chat".equals(action)) {
      return false;
    }
    return AgentSpanMetadata.KIND_TOOL.equals(
        AgentSpanMetadata.kindOf(readJson(step.getMetadataJson()), step.getAction(), step.getRole()));
  }

  private InterviewPlan extractPlan(List<AgentRunStepEntity> steps) {
    for (AgentRunStepEntity step : steps) {
      if (!"plan".equalsIgnoreCase(step.getAction())
          && !"plan_fallback".equalsIgnoreCase(step.getAction())) {
        continue;
      }
      InterviewPlan plan = readPlan(readJson(step.getObservation()), step.getObservation());
      if (plan != null) {
        return plan;
      }
    }
    return null;
  }

  private InterviewPlan readPlan(JsonNode node, String raw) {
    if (node != null && node.has("topics")) {
      try {
        return objectMapper.treeToValue(node, InterviewPlan.class);
      } catch (Exception ignored) {
        // fall through
      }
    }
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(raw, InterviewPlan.class);
    } catch (Exception ignored) {
      return null;
    }
  }

  private String summarizePlan(InterviewPlan plan) {
    if (plan.topics() == null || plan.topics().isEmpty()) {
      return firstNonBlank(plan.difficultyCurve(), "空大纲");
    }
    StringBuilder builder = new StringBuilder();
    if (plan.difficultyCurve() != null && !plan.difficultyCurve().isBlank()) {
      builder.append("难度曲线：").append(plan.difficultyCurve()).append('\n');
    }
    for (PlanTopic topic : plan.topics()) {
      builder.append("· ").append(topic.name());
      if (topic.focus() != null && !topic.focus().isBlank()) {
        builder.append(" — ").append(topic.focus());
      }
      builder.append("（约 ").append(topic.questionCount()).append(" 题）\n");
    }
    return builder.toString().strip();
  }

  private String resolveEmptyReason(PlaybackContext context, InterviewPlan plan) {
    if (context.agentMode() || plan != null) {
      return "STEPS_MISSING";
    }
    if (context.sessionExists()) {
      return "BATCH_FALLBACK";
    }
    return "NO_STEPS";
  }

  private String emptyHint(String reason) {
    if (reason == null) {
      return null;
    }
    return switch (reason) {
      case "STEPS_MISSING" -> "这场有大纲，但 agent_steps 没有行。轨迹写入失败不阻断出题，旧场次也可能丢步骤。新开一场文字模拟面试会立刻写入 PLANNING 和第一题。";
      case "BATCH_FALLBACK" -> "这是批量出题会话（编排关闭或创建时降级），不会有 planner / interviewer / critic 步骤。";
      default -> "还没有 Agent 步骤。创建文字模拟面试时会立刻写入 PLANNING 和第一题 ASKING/CRITIQUING。";
    };
  }

  private JsonNode readJson(String raw) {
    if (raw == null || raw.isBlank() || (!raw.trim().startsWith("{") && !raw.trim().startsWith("["))) {
      return null;
    }
    try {
      return objectMapper.readTree(raw);
    } catch (Exception ignored) {
      return null;
    }
  }

  private String text(JsonNode node, String field) {
    if (node == null || node.get(field) == null || node.get(field).isNull()) {
      return "";
    }
    return node.get(field).asText("");
  }

  private Boolean bool(JsonNode node, String field) {
    if (node == null || node.get(field) == null || node.get(field).isNull()) {
      return null;
    }
    return node.get(field).asBoolean();
  }

  private Integer integer(JsonNode node, String field) {
    if (node == null || node.get(field) == null || !node.get(field).isNumber()) {
      return null;
    }
    return node.get(field).asInt();
  }

  private List<String> stringList(JsonNode node, String field) {
    if (node == null || node.get(field) == null || !node.get(field).isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    node.get(field).forEach(item -> {
      if (item != null && !item.asText("").isBlank()) {
        values.add(item.asText());
      }
    });
    return List.copyOf(values);
  }

  private String capabilityLabel(JsonNode node) {
    if (node == null || node.isNull()) {
      return "";
    }
    String label = text(node, "label");
    if (!label.isBlank()) {
      return label;
    }
    return text(node, "id");
  }

  private String extractRetryHint(String input) {
    if (input == null) {
      return null;
    }
    String trimmed = input.trim();
    if (trimmed.toLowerCase(Locale.ROOT).startsWith("retryhint:")) {
      String hint = trimmed.substring("retryhint:".length()).trim();
      return hint.isBlank() ? trimmed : hint;
    }
    if (trimmed.toLowerCase(Locale.ROOT).contains("retryhint")) {
      return trimmed;
    }
    return null;
  }

  private String lastQuestion(List<AgentTraceEventDTO> events) {
    for (int i = events.size() - 1; i >= 0; i--) {
      AgentTraceEventDTO event = events.get(i);
      if (("ask".equalsIgnoreCase(event.action()) || "finish".equalsIgnoreCase(event.action()))
          && event.body() != null && !event.body().isBlank()) {
        return event.body();
      }
    }
    return null;
  }

  private String lastNonBlank(List<AgentTraceEventDTO> events,
                              java.util.function.Function<AgentTraceEventDTO, String> getter) {
    for (int i = events.size() - 1; i >= 0; i--) {
      String value = getter.apply(events.get(i));
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return "";
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  public record PlaybackContext(
      boolean sessionExists,
      boolean agentMode,
      InterviewPlan plan
  ) {
    public static PlaybackContext empty() {
      return new PlaybackContext(false, false, null);
    }
  }
}
