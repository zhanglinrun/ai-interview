package com.linrun.interview.modules.interview.agent;

import com.linrun.interview.modules.interview.agent.model.CapabilityAtom;
import com.linrun.interview.modules.interview.agent.model.CapabilityAtom.Source;
import com.linrun.interview.modules.interview.agent.model.InterviewEvidence.Bundle;
import com.linrun.interview.modules.interview.agent.model.InterviewPlan;
import com.linrun.interview.modules.interview.agent.model.InterviewPlan.PlanTopic;
import com.linrun.interview.modules.interview.agent.model.TurnDecision;
import com.linrun.interview.modules.interview.agent.model.TurnDecision.AnswerSignals;
import com.linrun.interview.modules.interview.agent.model.TurnDecision.FollowUpAction;
import com.linrun.interview.modules.interview.service.InterviewKnowledgeRetrievalService;
import com.linrun.interview.modules.interview.skill.InterviewSkillService;
import com.linrun.interview.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import com.linrun.interview.modules.interview.skill.InterviewSkillService.SkillDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 自适应追问决策器：用可复现规则从上一答提取信号，选择跟进动作与能力原子。
 *
 * <p>这里有意不做“答案正确性”判断。正确性仍由评估链负责；本服务只判断回答是否包含
 * 足够的因果、示例和取舍信号，因此无需增加一次 LLM 调用，也能给出可审计的决策依据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewTurnDecisionService {

  private static final int MIN_CLARIFIABLE_CHARS = 50;

  private static final List<String> REASONING_MARKERS = List.of(
      "因为", "所以", "因此", "由于", "原因", "导致", "原理", "本质",
      "because", "therefore");
  private static final List<String> EXAMPLE_MARKERS = List.of(
      "例如", "比如", "举例", "项目", "实践", "线上", "场景", "example");
  private static final List<String> TRADE_OFF_MARKERS = List.of(
      "取舍", "权衡", "优点", "缺点", "代价", "相比", "但是", "不过", "trade-off");
  private static final List<String> UNCERTAINTY_MARKERS = List.of(
      "不知道", "不清楚", "不会", "没了解", "不了解", "不确定", "记不清", "没用过",
      "don't know", "not sure");

  private final InterviewSkillService skillService;
  private final InterviewKnowledgeRetrievalService knowledgeRetrievalService;

  public TurnDecision decide(DecisionRequest request) {
    AnswerSignals signals = analyze(request.lastAnswer());
    PlanTopic plannedTopic = topicAt(request.plan(), request.questionIndex());
    PlanTopic previousTopic = topicAt(request.plan(), request.questionIndex() - 1);

    FollowUpAction action = chooseAction(request.questionIndex(), plannedTopic, previousTopic, signals);
    PlanTopic targetTopic = action == FollowUpAction.SWITCH_TOPIC || previousTopic == null
        ? plannedTopic : previousTopic;
    CapabilityAtom atom = resolveCapability(request.skillId(), targetTopic);
    String evidenceQuery = buildEvidenceQuery(atom, action);
    Bundle evidence = knowledgeRetrievalService.retrieveEvidence(
        request.knowledgeBaseIds(), evidenceQuery);
    String rationale = buildRationale(action, signals, plannedTopic, previousTopic);
    return new TurnDecision(action, atom, signals, rationale, evidence);
  }

  AnswerSignals analyze(String answer) {
    if (answer == null || answer.isBlank()) {
      return AnswerSignals.empty();
    }
    String normalized = answer.strip().toLowerCase(Locale.ROOT);
    int meaningfulChars = normalized.replaceAll("\\s+", "").length();
    return new AnswerSignals(
        meaningfulChars,
        containsAny(normalized, REASONING_MARKERS),
        containsAny(normalized, EXAMPLE_MARKERS),
        containsAny(normalized, TRADE_OFF_MARKERS),
        containsAny(normalized, UNCERTAINTY_MARKERS));
  }

  private FollowUpAction chooseAction(int questionIndex, PlanTopic plannedTopic,
                                      PlanTopic previousTopic, AnswerSignals signals) {
    if (questionIndex <= 0 || previousTopic == null) {
      return FollowUpAction.SWITCH_TOPIC;
    }
    if (signals.expressesUncertainty()) {
      return FollowUpAction.REMEDIATE;
    }
    if (signals.meaningfulChars() < MIN_CLARIFIABLE_CHARS
        || !signals.hasReasoning()
        || (!signals.hasExample() && !signals.hasTradeOff())) {
      return FollowUpAction.CLARIFY;
    }
    return sameTopic(plannedTopic, previousTopic)
        ? FollowUpAction.DEEPEN : FollowUpAction.SWITCH_TOPIC;
  }

  private CapabilityAtom resolveCapability(String skillId, PlanTopic topic) {
    String topicName = topic != null && topic.name() != null && !topic.name().isBlank()
        ? topic.name().strip() : fallbackLabel(skillId);
    String focus = topic != null && topic.focus() != null ? topic.focus().strip() : "综合能力考察";
    Optional<SkillCategoryDTO> category = findSkillCategory(skillId, topicName, focus);
    if (category.isPresent()) {
      SkillCategoryDTO matched = category.get();
      return new CapabilityAtom(
          "skill:" + safeIdPart(skillId) + ":" + safeIdPart(matched.key()),
          matched.label(),
          focus,
          Source.SKILL,
          matched.priority());
    }

    Source source = InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId) ? Source.JD : Source.PLAN;
    // JD/Planner 主题也必须跨会话稳定，否则画像永远无法积累到 VERIFIED。
    // 同名 JD 能力有意跨岗位复用；普通大纲能力再用 skillId 隔离不同面试方向。
    String scope = source == Source.JD
        ? "jd"
        : "plan:" + safeIdPart(skillId);
    return new CapabilityAtom(
        scope + ":" + safeIdPart(topicName),
        topicName,
        focus,
        source,
        null);
  }

  private Optional<SkillCategoryDTO> findSkillCategory(String skillId, String topicName,
                                                       String focus) {
    if (skillId == null || skillId.isBlank()
        || InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId)) {
      return Optional.empty();
    }
    try {
      SkillDTO skill = skillService.getSkill(skillId);
      if (skill == null || skill.categories() == null) {
        return Optional.empty();
      }
      String normalizedTopic = normalizeForMatch(topicName);
      String normalizedFocus = normalizeForMatch(focus);
      return skill.categories().stream()
          .max(Comparator.comparingInt(category -> matchScore(
              category, normalizedTopic, normalizedFocus)))
          .filter(category -> matchScore(category, normalizedTopic, normalizedFocus) > 0);
    } catch (Exception e) {
      log.debug("能力原子未匹配到预设 Skill，退回会话级大纲原子: skillId={}", skillId);
      return Optional.empty();
    }
  }

  private int matchScore(SkillCategoryDTO category, String topic, String focus) {
    String label = normalizeForMatch(category.label());
    String key = normalizeForMatch(category.key());
    if ((!label.isBlank() && topic.equals(label)) || (!key.isBlank() && topic.equals(key))) {
      return 100;
    }
    if ((!label.isBlank() && topic.contains(label)) || (!key.isBlank() && topic.contains(key))) {
      return 70;
    }
    if ((!label.isBlank() && focus.contains(label)) || (!key.isBlank() && focus.contains(key))) {
      return 40;
    }
    return 0;
  }

  private String buildEvidenceQuery(CapabilityAtom atom, FollowUpAction action) {
    String actionHint = switch (action) {
      case DEEPEN -> "边界条件 工程取舍";
      case CLARIFY -> "核心原理 判断依据";
      case REMEDIATE -> "基础原理 常见误区";
      case SWITCH_TOPIC -> "核心知识点 面试考点";
    };
    return (atom.label() + " " + atom.description() + " " + actionHint).strip();
  }

  private String buildRationale(FollowUpAction action, AnswerSignals signals,
                                PlanTopic plannedTopic, PlanTopic previousTopic) {
    return switch (action) {
      case REMEDIATE -> "上一答明确表达不确定，暂不切换大纲节点，先用更低脚手架问题复核基础。";
      case CLARIFY -> "上一答缺少足够的因果、示例或取舍信号，保留当前能力原子并要求补充。";
      case DEEPEN -> "上一答包含因果及实践/取舍信号，且计划仍处于同一主题，继续深挖边界。";
      case SWITCH_TOPIC -> previousTopic == null
          ? "首题按 Planner 大纲进入目标能力原子。"
          : "上一答具备展开信号，Planner 已进入下一主题，切换能力原子以保证覆盖。";
    };
  }

  private PlanTopic topicAt(InterviewPlan plan, int index) {
    if (plan == null || index < 0) {
      return null;
    }
    return plan.topicForQuestion(index);
  }

  private boolean sameTopic(PlanTopic left, PlanTopic right) {
    if (left == null || right == null) {
      return false;
    }
    return normalizeForMatch(left.name()).equals(normalizeForMatch(right.name()));
  }

  private boolean containsAny(String text, List<String> markers) {
    return markers.stream().anyMatch(text::contains);
  }

  private String normalizeForMatch(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
  }

  private String safeIdPart(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    String normalized = value.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("(^-+|-+$)", "");
    if (!normalized.isBlank()) {
      return normalized;
    }
    return "topic-" + Integer.toUnsignedString(value.hashCode(), 36);
  }

  private String fallbackLabel(String skillId) {
    return skillId == null || skillId.isBlank() ? "综合能力" : skillId;
  }

  public record DecisionRequest(
      String sessionId,
      String skillId,
      int questionIndex,
      InterviewPlan plan,
      String lastAnswer,
      List<Long> knowledgeBaseIds
  ) {
    public DecisionRequest {
      knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
    }
  }
}
