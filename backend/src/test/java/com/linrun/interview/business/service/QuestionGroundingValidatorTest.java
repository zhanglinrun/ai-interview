package com.linrun.interview.business.service;

import com.linrun.interview.business.vo.AgentQuestionOutput;
import com.linrun.interview.business.vo.CapabilityAtom;
import com.linrun.interview.business.vo.CapabilityAtom.Source;
import com.linrun.interview.business.vo.InterviewEvidence;
import com.linrun.interview.business.vo.InterviewEvidence.Bundle;
import com.linrun.interview.business.vo.TurnDecision;
import com.linrun.interview.business.vo.TurnDecision.AnswerSignals;
import com.linrun.interview.business.vo.TurnDecision.FollowUpAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("出题 grounded 校验")
class QuestionGroundingValidatorTest {

  private final TurnDecision decision = new TurnDecision(
      FollowUpAction.SWITCH_TOPIC,
      new CapabilityAtom("atom-1", "Redis", "持久化", Source.PLAN, null),
      AnswerSignals.empty(),
      "test",
      new Bundle(
          "q",
          List.of(new InterviewEvidence("ev-1", 1L, "c1", null, "a.md", "doc", 0.9, "snippet")),
          List.of(new InterviewEvidence("ev-1", 1L, "c1", null, "a.md", "doc", 0.9, "snippet"))));

  @Nested
  @DisplayName("evidence_ids")
  class EvidenceIds {

    @Test
    @DisplayName("合法 ID 通过")
    void acceptsKnownEvidenceId() {
      AgentQuestionOutput output = new AgentQuestionOutput(
          "请说明 RDB 与 AOF 的取舍", "基于证据", false, List.of("ev-1"));
      assertThat(QuestionGroundingValidator.validate(output, decision, null).grounded()).isTrue();
    }

    @Test
    @DisplayName("编造 ID 打回")
    void rejectsFabricatedEvidenceId() {
      AgentQuestionOutput output = new AgentQuestionOutput(
          "请说明 RDB", "编造来源", false, List.of("ev-fake"));
      var verdict = QuestionGroundingValidator.validate(output, decision, null);
      assertThat(verdict.grounded()).isFalse();
      assertThat(verdict.retryHint()).contains("ev-fake");
    }
  }

  @Nested
  @DisplayName("简历专名")
  class ResumeNames {

    @Test
    @DisplayName("书名号项目名出现在简历中则通过")
    void acceptsResumeProjectName() {
      AgentQuestionOutput output = new AgentQuestionOutput(
          "在《支付中台》里你们如何做幂等？", "简历项目", false, List.of());
      var verdict = QuestionGroundingValidator.validate(
          output, decision, "项目经历：负责《支付中台》的订单链路");
      assertThat(verdict.grounded()).isTrue();
    }

    @Test
    @DisplayName("书名号项目名不见于简历则打回")
    void rejectsUnknownProjectName() {
      AgentQuestionOutput output = new AgentQuestionOutput(
          "在《星际交易系统》里你们如何做幂等？", "编造项目", false, List.of());
      var verdict = QuestionGroundingValidator.validate(
          output, decision, "项目经历：负责《支付中台》的订单链路");
      assertThat(verdict.grounded()).isFalse();
      assertThat(verdict.retryHint()).contains("星际交易系统");
    }
  }
}
