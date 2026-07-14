package com.linrun.interview.modules.interview.agent;

import com.linrun.interview.modules.interview.agent.InterviewTurnDecisionService.DecisionRequest;
import com.linrun.interview.modules.interview.agent.model.CapabilityAtom.Source;
import com.linrun.interview.modules.interview.agent.model.InterviewEvidence.Bundle;
import com.linrun.interview.modules.interview.agent.model.InterviewPlan;
import com.linrun.interview.modules.interview.agent.model.InterviewPlan.PlanTopic;
import com.linrun.interview.modules.interview.agent.model.TurnDecision;
import com.linrun.interview.modules.interview.agent.model.TurnDecision.FollowUpAction;
import com.linrun.interview.modules.interview.service.InterviewKnowledgeRetrievalService;
import com.linrun.interview.modules.interview.skill.InterviewSkillService;
import com.linrun.interview.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import com.linrun.interview.modules.interview.skill.InterviewSkillService.SkillDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("面试逐轮决策器测试")
class InterviewTurnDecisionServiceTest {

  private InterviewTurnDecisionService service;

  @BeforeEach
  void setUp() {
    InterviewSkillService skillService = mock(InterviewSkillService.class);
    when(skillService.getSkill("java-backend")).thenReturn(new SkillDTO(
        "java-backend", "Java 后端", "后端开发", List.of(
            new SkillCategoryDTO("MYSQL", "MySQL", "CORE", "mysql.md", true),
            new SkillCategoryDTO("REDIS", "Redis", "CORE", "redis.md", true)),
        true, null, null, null));
    InterviewKnowledgeRetrievalService retrievalService =
        mock(InterviewKnowledgeRetrievalService.class);
    when(retrievalService.retrieveEvidence(anyList(), anyString()))
        .thenAnswer(invocation -> Bundle.empty(invocation.getArgument(1)));
    service = new InterviewTurnDecisionService(skillService, retrievalService);
  }

  @Nested
  @DisplayName("动作选择")
  class ActionSelection {

    @Test
    @DisplayName("首题按 Planner 节点进入稳定 Skill 能力原子")
    void startsWithPlannedCapability() {
      TurnDecision decision = service.decide(request(0, plan(1), null));

      assertThat(decision.action()).isEqualTo(FollowUpAction.SWITCH_TOPIC);
      assertThat(decision.targetCapability().id()).isEqualTo("skill:java-backend:mysql");
      assertThat(decision.targetCapability().source()).isEqualTo(Source.SKILL);
      assertThat(decision.requiresFollowUp()).isFalse();
    }

    @Test
    @DisplayName("明确不会时不切换计划主题，降低脚手架复核当前能力")
    void remediatesExplicitUncertainty() {
      TurnDecision decision = service.decide(request(
          1, plan(1), "这个我不太清楚，之前也没用过。"));

      assertThat(decision.action()).isEqualTo(FollowUpAction.REMEDIATE);
      assertThat(decision.targetCapability().label()).isEqualTo("MySQL");
      assertThat(decision.answerSignals().expressesUncertainty()).isTrue();
      assertThat(decision.requiresFollowUp()).isTrue();
    }

    @Test
    @DisplayName("短回答缺少展开信号时要求澄清而不是盲目切题")
    void clarifiesShallowAnswer() {
      TurnDecision decision = service.decide(request(1, plan(1), "用索引会更快。"));

      assertThat(decision.action()).isEqualTo(FollowUpAction.CLARIFY);
      assertThat(decision.targetCapability().label()).isEqualTo("MySQL");
      assertThat(decision.answerSignals().meaningfulChars()).isLessThan(50);
    }

    @Test
    @DisplayName("回答有因果和实践信号且大纲换题时切换能力")
    void switchesAfterSubstantiveAnswer() {
      String answer = "因为 InnoDB 的聚簇索引叶子节点保存整行数据，所以回表成本取决于命中行数。"
          + "例如我在项目里用覆盖索引减少随机 IO，但是索引变多也会增加写放大和维护代价。";

      TurnDecision decision = service.decide(request(1, plan(1), answer));

      assertThat(decision.action()).isEqualTo(FollowUpAction.SWITCH_TOPIC);
      assertThat(decision.targetCapability().id()).isEqualTo("skill:java-backend:redis");
      assertThat(decision.answerSignals().hasReasoning()).isTrue();
      assertThat(decision.answerSignals().hasExample()).isTrue();
      assertThat(decision.answerSignals().hasTradeOff()).isTrue();
    }

    @Test
    @DisplayName("回答有展开信号且大纲仍在同一能力时继续深挖")
    void deepensWithinSameCapability() {
      String answer = "因为联合索引遵循最左前缀，所以查询条件顺序由优化器处理，但可用前缀仍受字段约束。"
          + "例如项目里把高区分度字段放进覆盖索引，不过代价是写入和存储开销增加。";

      TurnDecision decision = service.decide(request(1, plan(2), answer));

      assertThat(decision.action()).isEqualTo(FollowUpAction.DEEPEN);
      assertThat(decision.targetCapability().label()).isEqualTo("MySQL");
      assertThat(decision.requiresFollowUp()).isTrue();
    }

    @Test
    @DisplayName("同一 JD 主题跨会话复用能力原子以累计验证观测")
    void keepsJdCapabilityStableAcrossSessions() {
      InterviewPlan customPlan = new InterviewPlan(List.of(
          new PlanTopic("RAG 检索优化", "混合检索、Rerank 与召回质量", 2)),
          "由浅入深", List.of(), List.of());

      TurnDecision first = service.decide(new DecisionRequest(
          "session-1", InterviewSkillService.CUSTOM_SKILL_ID,
          0, customPlan, null, List.of()));
      TurnDecision second = service.decide(new DecisionRequest(
          "session-2", InterviewSkillService.CUSTOM_SKILL_ID,
          0, customPlan, null, List.of()));

      assertThat(first.targetCapability().source()).isEqualTo(Source.JD);
      assertThat(first.targetCapability().id())
          .isEqualTo(second.targetCapability().id())
          .startsWith("jd:");
    }
  }

  private DecisionRequest request(int questionIndex, InterviewPlan plan, String answer) {
    return new DecisionRequest(
        "session-1", "java-backend", questionIndex, plan, answer, List.of());
  }

  private InterviewPlan plan(int mysqlQuestionCount) {
    return new InterviewPlan(List.of(
        new PlanTopic("MySQL 索引与事务", "索引原理、事务边界与工程取舍", mysqlQuestionCount),
        new PlanTopic("Redis", "缓存一致性与故障场景", 2)),
        "由浅入深", List.of(), List.of());
  }
}
