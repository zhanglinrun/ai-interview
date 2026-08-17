package com.linrun.interview.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.entity.AgentRunStepEntity;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.service.AgentTraceInterpreter.PlaybackContext;
import com.linrun.interview.business.vo.AgentTraceCatalogItemDTO;
import com.linrun.interview.business.vo.AgentTracePlaybackDTO;
import com.linrun.interview.business.vo.InterviewPlan;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Trace 回放：按文字模拟面试 sessionId 收集并解释步骤。
 */
@Service
public class AgentTracePlaybackService {

  private final AgentTraceService agentTraceService;
  private final InterviewPersistenceService persistenceService;
  private final ObjectMapper objectMapper;
  private final AgentTraceInterpreter interpreter;
  private final AgentTraceTreeBuilder treeBuilder;

  public AgentTracePlaybackService(AgentTraceService agentTraceService,
                                   InterviewPersistenceService persistenceService,
                                   ObjectMapper objectMapper) {
    this.agentTraceService = agentTraceService;
    this.persistenceService = persistenceService;
    this.objectMapper = objectMapper;
    this.interpreter = new AgentTraceInterpreter(objectMapper);
    this.treeBuilder = new AgentTraceTreeBuilder(objectMapper);
  }

  public List<AgentTraceCatalogItemDTO> listCatalog() {
    Long userId = UserContext.requireUserId();
    List<InterviewSessionEntity> sessions = persistenceService.findAll();
    List<AgentRunStepEntity> allSteps = agentTraceService.listByUser(userId);
    Map<String, List<AgentRunStepEntity>> bySession = new LinkedHashMap<>();
    for (AgentRunStepEntity step : allSteps) {
      if (step.getSessionId() == null || step.getSessionId().isBlank()) {
        continue;
      }
      bySession.computeIfAbsent(step.getSessionId(), key -> new ArrayList<>()).add(step);
    }

    List<AgentTraceCatalogItemDTO> items = new ArrayList<>();
    for (InterviewSessionEntity session : sessions) {
      List<AgentRunStepEntity> steps = bySession.getOrDefault(session.getSessionId(), List.of());
      items.add(toCatalogItem(session, steps));
    }
    for (Map.Entry<String, List<AgentRunStepEntity>> entry : bySession.entrySet()) {
      if (sessions.stream().anyMatch(session -> entry.getKey().equals(session.getSessionId()))) {
        continue;
      }
      items.add(orphanItem(entry.getKey(), entry.getValue()));
    }
    items.sort(Comparator
        .comparingInt(AgentTraceCatalogItemDTO::stepCount).reversed()
        .thenComparing(AgentTraceCatalogItemDTO::createdAt,
            Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(AgentTraceCatalogItemDTO::sessionId));
    return items;
  }

  public AgentTracePlaybackDTO getPlayback(String sessionId) {
    Long userId = UserContext.requireUserId();
    InterviewSessionEntity session = persistenceService.findBySessionId(sessionId).orElse(null);
    List<String> sourceIds = sourceIds(sessionId);
    List<AgentRunStepEntity> steps = agentTraceService.listBySessionKeys(userId, sourceIds);
    InterviewPlan plan = session == null ? null : parsePlanQuietly(session.getInterviewPlanJson());
    boolean agentMode = session != null && session.getInterviewPlanJson() != null
        && !session.getInterviewPlanJson().isBlank();
    return interpreter.interpret(sessionId, sourceIds, steps,
            new PlaybackContext(session != null, agentMode, plan))
        .withSpans(treeBuilder.build(steps));
  }

  public List<AgentRunStepEntity> loadOwnedSteps(String sessionId) {
    Long userId = UserContext.requireUserId();
    return agentTraceService.listBySessionKeys(userId, sourceIds(sessionId));
  }

  private AgentTraceCatalogItemDTO toCatalogItem(InterviewSessionEntity session,
                                                 List<AgentRunStepEntity> steps) {
    AgentRunStepEntity last = steps.isEmpty() ? null : steps.get(steps.size() - 1);
    String lastState = last == null
        ? null
        : interpreter.resolveState(last.getRole(), last.getAction(), last.getActionInput());
    String label = "文字面试 · " + shortId(session.getSessionId())
        + " · " + (session.getStatus() == null ? "?" : session.getStatus().name())
        + " · " + steps.size() + " 步";
    return new AgentTraceCatalogItemDTO(
        session.getSessionId(),
        label,
        session.getStatus() == null ? null : session.getStatus().name(),
        session.getTotalQuestions() == null ? 0 : session.getTotalQuestions(),
        false,
        session.getInterviewPlanJson() != null && !session.getInterviewPlanJson().isBlank(),
        steps.size(),
        lastState,
        session.getCreatedAt());
  }

  private AgentTraceCatalogItemDTO orphanItem(String sessionId, List<AgentRunStepEntity> steps) {
    AgentRunStepEntity last = steps.isEmpty() ? null : steps.get(steps.size() - 1);
    String lastState = last == null
        ? null
        : interpreter.resolveState(last.getRole(), last.getAction(), last.getActionInput());
    LocalDateTime createdAt = steps.isEmpty() ? null : steps.get(0).getCreatedAt();
    return new AgentTraceCatalogItemDTO(
        sessionId,
        "编排运行 · " + shortId(sessionId) + " · " + steps.size() + " 步",
        lastState,
        0,
        true,
        false,
        steps.size(),
        lastState,
        createdAt);
  }

  private List<String> sourceIds(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return List.of();
    }
    return List.of(sessionId);
  }

  private InterviewPlan parsePlanQuietly(String planJson) {
    if (planJson == null || planJson.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(planJson, InterviewPlan.class);
    } catch (Exception ignored) {
      return null;
    }
  }

  private String shortId(String sessionId) {
    if (sessionId == null || sessionId.length() <= 8) {
      return sessionId == null ? "" : sessionId;
    }
    return sessionId.substring(0, 8) + "…";
  }
}
