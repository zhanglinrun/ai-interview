package com.linrun.interview.business.vo;

import com.linrun.interview.business.vo.TurnDecision.AnswerSignals;
import java.util.ArrayList;
import java.util.List;

/**
 * 本场已完成主问题的短摘要，供出题/审题 Prompt 使用，不依赖 ChatMemory 窗口。
 *
 * @param questionIndex  主问题题号
 * @param topicSummary   知识点摘要
 * @param answerSignals  候选人作答结构信号；未分析时为空
 * @param followUpAction 该答之后的跟进动作；刚答完尚未决策时可为 null，由编排器填入本轮动作
 */
public record AskedTurnSummary(
    int questionIndex,
    String topicSummary,
    AnswerSignals answerSignals,
    String followUpAction
) {

  public AskedTurnSummary {
    topicSummary = topicSummary == null || topicSummary.isBlank() ? "未命名主题" : topicSummary.strip();
    answerSignals = answerSignals == null ? AnswerSignals.empty() : answerSignals;
    followUpAction = followUpAction == null || followUpAction.isBlank() ? null : followUpAction.strip();
  }

  public static AskedTurnSummary fromQuestion(InterviewQuestionDTO question, String actionAfterAnswer) {
    String topic = question.topicSummary() != null && !question.topicSummary().isBlank()
        ? question.topicSummary()
        : (question.category() != null ? question.category() : "综合能力");
    return new AskedTurnSummary(
        question.questionIndex(),
        topic.replace("（追问）", "").strip(),
        question.answerSignals(),
        actionAfterAnswer != null ? actionAfterAnswer : question.followUpAction());
  }

  /** 已作答主问题压成摘要；追问不单独占一条，跟进动作取随后一题。 */
  public static List<AskedTurnSummary> fromAnsweredMains(List<InterviewQuestionDTO> questions) {
    if (questions == null || questions.isEmpty()) {
      return List.of();
    }
    List<AskedTurnSummary> summaries = new ArrayList<>();
    for (int i = 0; i < questions.size(); i++) {
      InterviewQuestionDTO question = questions.get(i);
      if (question.isFollowUp()
          || question.userAnswer() == null
          || question.userAnswer().isBlank()) {
        continue;
      }
      String actionAfter = null;
      for (int j = i + 1; j < questions.size(); j++) {
        InterviewQuestionDTO next = questions.get(j);
        if (next.followUpAction() != null && !next.followUpAction().isBlank()) {
          actionAfter = next.followUpAction();
          break;
        }
      }
      summaries.add(fromQuestion(question, actionAfter));
    }
    return List.copyOf(summaries);
  }

  public String toPromptLine() {
    StringBuilder sb = new StringBuilder();
    sb.append("- [#").append(questionIndex).append("] ").append(topicSummary);
    sb.append("｜信号=").append(formatSignals(answerSignals));
    if (followUpAction != null) {
      sb.append("｜跟进=").append(followUpAction);
    }
    return sb.toString();
  }

  private static String formatSignals(AnswerSignals signals) {
    if (signals.meaningfulChars() <= 0) {
      return "empty";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("chars=").append(signals.meaningfulChars());
    if (signals.hasReasoning()) {
      sb.append(",reasoning");
    }
    if (signals.hasExample()) {
      sb.append(",example");
    }
    if (signals.hasTradeOff()) {
      sb.append(",tradeOff");
    }
    if (signals.expressesUncertainty()) {
      sb.append(",uncertain");
    }
    return sb.toString();
  }
}
