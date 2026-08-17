package com.linrun.interview.business.vo;

import com.linrun.interview.business.vo.TurnDecision.AnswerSignals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("已问主问题摘要")
class AskedTurnSummaryTest {

  @Test
  @DisplayName("fromQuestion 拼出可读 prompt 行")
  void formatsPromptLine() {
    InterviewQuestionDTO question = InterviewQuestionDTO.createAgent(
            0, "请对比 RDB/AOF", "atom", "Redis", "Redis 持久化",
            false, null, "atom", "SWITCH_TOPIC", java.util.List.of())
        .withAnswer("因为要权衡性能，例如线上用 AOF")
        .withAnswerSignals(new AnswerSignals(20, true, true, true, false));

    AskedTurnSummary summary = AskedTurnSummary.fromQuestion(question, "DEEPEN");
    assertThat(summary.toPromptLine())
        .contains("#0")
        .contains("Redis 持久化")
        .contains("reasoning")
        .contains("跟进=DEEPEN");
  }

  @Test
  @DisplayName("只压缩已作答主问题，追问不单独占一条")
  void compressesAnsweredMainsOnly() {
    InterviewQuestionDTO main = InterviewQuestionDTO.createAgent(
            0, "请对比 RDB/AOF", "atom", "Redis", "Redis 持久化",
            false, null, "atom", "SWITCH_TOPIC", java.util.List.of())
        .withAnswer("AOF 更安全");
    InterviewQuestionDTO followUp = InterviewQuestionDTO.createAgent(
            1, "失败窗口怎么处理？", "atom", "Redis", "Redis 持久化（追问）",
            true, 0, "atom", "DEEPEN", java.util.List.of())
        .withAnswer("会丢最近一秒");
    InterviewQuestionDTO unanswered = InterviewQuestionDTO.createAgent(
            2, "下一题", "atom", "MySQL", "事务",
            false, null, "atom", "SWITCH_TOPIC", java.util.List.of());

    var summaries = AskedTurnSummary.fromAnsweredMains(java.util.List.of(main, followUp, unanswered));

    assertThat(summaries).hasSize(1);
    assertThat(summaries.getFirst().topicSummary()).isEqualTo("Redis 持久化");
    assertThat(summaries.getFirst().followUpAction()).isEqualTo("DEEPEN");
  }
}
