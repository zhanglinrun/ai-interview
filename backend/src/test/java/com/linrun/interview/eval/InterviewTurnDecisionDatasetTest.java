package com.linrun.interview.eval;

import com.linrun.interview.modules.interview.agent.InterviewTurnDecisionService;
import com.linrun.interview.modules.interview.agent.InterviewTurnDecisionService.DecisionRequest;
import com.linrun.interview.modules.interview.agent.model.InterviewEvidence.Bundle;
import com.linrun.interview.modules.interview.agent.model.InterviewPlan;
import com.linrun.interview.modules.interview.agent.model.InterviewPlan.PlanTopic;
import com.linrun.interview.modules.interview.agent.model.TurnDecision;
import com.linrun.interview.modules.interview.service.InterviewKnowledgeRetrievalService;
import com.linrun.interview.modules.interview.skill.InterviewSkillService;
import com.linrun.interview.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import com.linrun.interview.modules.interview.skill.InterviewSkillService.SkillDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("自适应下一题决策数据集回归")
class InterviewTurnDecisionDatasetTest {

  private static final String DATASET = "turn-decision-eval/decision-cases.yaml";

  @Test
  @DisplayName("动作、目标能力与追问语义全部符合标注")
  void matchesDecisionDataset() {
    InterviewTurnDecisionService service = buildService();
    List<Case> cases = loadCases();

    assertThat(cases).isNotEmpty();
    for (Case c : cases) {
      InterviewPlan plan = new InterviewPlan(List.of(
          new PlanTopic("MySQL 索引与事务", "索引原理、事务边界与工程取舍",
              c.mysqlQuestionCount),
          new PlanTopic("Redis", "缓存一致性与故障场景", 2)),
          "由浅入深", List.of(), List.of());
      TurnDecision decision = service.decide(new DecisionRequest(
          "dataset-session", "java-backend", c.questionIndex, plan,
          c.lastAnswer, List.of()));

      assertThat(decision.action().name())
          .as("case %s action", c.id)
          .isEqualTo(c.expectedAction);
      assertThat(decision.targetCapability().label())
          .as("case %s target", c.id)
          .isEqualTo(c.expectedTarget);
      assertThat(decision.requiresFollowUp())
          .as("case %s follow-up", c.id)
          .isEqualTo(c.expectedFollowUp);
    }
  }

  private InterviewTurnDecisionService buildService() {
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
    return new InterviewTurnDecisionService(skillService, retrievalService);
  }

  @SuppressWarnings("unchecked")
  private List<Case> loadCases() {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(DATASET)) {
      assertThat(input).as("decision dataset resource").isNotNull();
      Map<String, Object> root = new Yaml().load(input);
      List<Map<String, Object>> rawCases = (List<Map<String, Object>>) root.get("cases");
      List<Case> cases = new ArrayList<>();
      for (Map<String, Object> raw : rawCases) {
        cases.add(new Case(
            String.valueOf(raw.get("id")),
            ((Number) raw.get("questionIndex")).intValue(),
            ((Number) raw.get("mysqlQuestionCount")).intValue(),
            raw.get("lastAnswer") == null ? null : String.valueOf(raw.get("lastAnswer")),
            String.valueOf(raw.get("expectedAction")),
            String.valueOf(raw.get("expectedTarget")),
            Boolean.TRUE.equals(raw.get("expectedFollowUp"))));
      }
      return cases;
    } catch (Exception e) {
      throw new IllegalStateException("读取自适应下一题评测集失败", e);
    }
  }

  private record Case(
      String id,
      int questionIndex,
      int mysqlQuestionCount,
      String lastAnswer,
      String expectedAction,
      String expectedTarget,
      boolean expectedFollowUp
  ) {}
}
