package com.linrun.interview.business.vo;

import com.linrun.interview.business.vo.InterviewEvidence.Bundle;

/**
 * 一轮自适应出题的可解释决策。
 *
 * @param action           本轮跟进动作
 * @param targetCapability 目标能力原子
 * @param answerSignals    从上一答确定性提取的信号，不声称等同于正确性评分
 * @param rationale        选择该动作与能力的规则依据
 * @param evidence         本轮检索候选及送入模型的证据
 */
public record TurnDecision(
    FollowUpAction action,
    CapabilityAtom targetCapability,
    AnswerSignals answerSignals,
    String rationale,
    Bundle evidence
) {

  public enum FollowUpAction {
    /** 围绕回答中已经出现的技术点继续追问边界与取舍。 */
    DEEPEN,
    /** 回答缺少因果、示例或关键约束，要求候选人补充说明。 */
    CLARIFY,
    /** 候选人明确表示不会或不确定，降低脚手架难度后再验证。 */
    REMEDIATE,
    /** 当前能力已完成计划轮次，切换到下一个能力原子。 */
    SWITCH_TOPIC
  }

  public record AnswerSignals(
      int meaningfulChars,
      boolean hasReasoning,
      boolean hasExample,
      boolean hasTradeOff,
      boolean expressesUncertainty
  ) {
    public static AnswerSignals empty() {
      return new AnswerSignals(0, false, false, false, false);
    }
  }

  public TurnDecision {
    answerSignals = answerSignals == null ? AnswerSignals.empty() : answerSignals;
    rationale = rationale == null ? "" : rationale;
    evidence = evidence == null ? Bundle.empty("") : evidence;
  }

  public boolean requiresFollowUp() {
    return action != FollowUpAction.SWITCH_TOPIC;
  }
}

