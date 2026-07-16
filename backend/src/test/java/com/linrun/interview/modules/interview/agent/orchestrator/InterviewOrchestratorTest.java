package com.linrun.interview.modules.interview.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.modules.interview.agent.AgentAiServiceFactory;
import com.linrun.interview.modules.interview.agent.AgentOrchestrationProperties;
import com.linrun.interview.modules.interview.agent.AgentTraceService;
import com.linrun.interview.modules.interview.agent.CriticAiService;
import com.linrun.interview.modules.interview.agent.InterviewTurnDecisionService;
import com.linrun.interview.modules.interview.agent.InterviewerAiService;
import com.linrun.interview.modules.interview.agent.model.AgentQuestionOutput;
import com.linrun.interview.modules.interview.agent.model.CriticVerdict;
import com.linrun.interview.modules.interview.agent.model.InterviewPlan;
import com.linrun.interview.modules.interview.agent.model.InterviewPlan.PlanTopic;
import com.linrun.interview.modules.interview.agent.orchestrator.InterviewOrchestrator.GeneratedQuestion;
import com.linrun.interview.modules.interview.agent.orchestrator.InterviewOrchestrator.NextQuestionRequest;
import com.linrun.interview.modules.interview.memory.CandidateMemoryService;
import com.linrun.interview.modules.interview.service.InterviewKnowledgeRetrievalService;
import com.linrun.interview.modules.interview.topic.InterviewTopicCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InterviewOrchestrator} 状态机与 Reflexion 反思环单元测试。
 *
 * <p>覆盖 ASKING→CRITIQUING 循环的三条核心路径：Critic 通过直出、Critic 打回后携带
 * retryHint 重生成、Reflexion 达上限短路；外加 Critic 关闭直出与 Interviewer 失败兜底。
 * 全部通过 mock 四角色工厂驱动，不触达真实 LLM。
 */
@DisplayName("Multi-Agent 面试编排器测试")
class InterviewOrchestratorTest {

  private static final String PROVIDER = "test-provider";

  private AgentAiServiceFactory factory;
  private InterviewerAiService interviewer;
  private CriticAiService critic;
  private AgentOrchestrationProperties properties;
  private InterviewOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    factory = mock(AgentAiServiceFactory.class);
    interviewer = mock(InterviewerAiService.class);
    critic = mock(CriticAiService.class);
    when(factory.interviewer(anyLong())).thenReturn(interviewer);
    when(factory.critic(anyLong())).thenReturn(critic);

    properties = new AgentOrchestrationProperties();
    properties.setMetricsEnabled(false); // meterRegistry 传 null，关闭指标避免 NPE

    AgentTraceService traceService = mock(AgentTraceService.class);
    CandidateMemoryService candidateMemoryService = mock(CandidateMemoryService.class);
    InterviewKnowledgeRetrievalService knowledgeRetrievalService =
        mock(InterviewKnowledgeRetrievalService.class);
    when(knowledgeRetrievalService.buildEvidencePrompt(any())).thenReturn("");
    InterviewTurnDecisionService turnDecisionService = new InterviewTurnDecisionService(
        mock(InterviewTopicCatalog.class), knowledgeRetrievalService);
    orchestrator = new InterviewOrchestrator(factory, properties, traceService,
        candidateMemoryService, knowledgeRetrievalService, turnDecisionService, new ObjectMapper(),
        null);
  }

  private NextQuestionRequest request() {
    return new NextQuestionRequest(
        "session-1", 1L, PROVIDER, "java-backend", "mid",
        0, 8, null, null, List.of(), null, List.of());
  }

  private AgentQuestionOutput output(String question) {
    return new AgentQuestionOutput(question, "rationale for " + question, false);
  }

  @Nested
  @DisplayName("状态机路径")
  class StateMachine {

    @Test
    @DisplayName("Critic 通过：首题直接产出，reflexionRounds=0 且 approved=true")
    void approvesFirstQuestion() {
      properties.setCriticEnabled(true);
      properties.setMaxReflexion(2);
      when(interviewer.nextQuestion(anyString(), anyString(), anyString(), anyString()))
          .thenReturn(output("Q1"));
      when(critic.review(anyString()))
          .thenReturn(new CriticVerdict(true, 90, "契合大纲且具体", ""));

      GeneratedQuestion result = orchestrator.nextQuestion(request());

      assertThat(result.question()).isEqualTo("Q1");
      assertThat(result.reflexionRounds()).isZero();
      assertThat(result.criticApproved()).isTrue();
      verify(interviewer, times(1))
          .nextQuestion(anyString(), anyString(), anyString(), anyString());
      verify(critic, times(1)).review(anyString());
    }

    @Test
    @DisplayName("Critic 打回一次后通过：reflexionRounds=1，重出题携带 retryHint")
    void regeneratesOnceThenApproves() {
      properties.setCriticEnabled(true);
      properties.setMaxReflexion(2);
      when(interviewer.nextQuestion(anyString(), anyString(), anyString(), anyString()))
          .thenReturn(output("Q1"), output("Q2"));
      when(critic.review(anyString()))
          .thenReturn(new CriticVerdict(false, 40, "太宽泛", "请聚焦 JVM 垃圾回收细节"),
              new CriticVerdict(true, 85, "改进后达标", ""));

      GeneratedQuestion result = orchestrator.nextQuestion(request());

      assertThat(result.question()).isEqualTo("Q2");
      assertThat(result.reflexionRounds()).isEqualTo(1);
      assertThat(result.criticApproved()).isTrue();

      ArgumentCaptor<String> instructionCaptor = ArgumentCaptor.forClass(String.class);
      verify(interviewer, times(2))
          .nextQuestion(anyString(), anyString(), anyString(), instructionCaptor.capture());
      verify(critic, times(2)).review(anyString());
      // 第二次出题的 instruction 必须带上 Critic 的 retryHint（Reflexion 输入）
      assertThat(instructionCaptor.getAllValues().get(1)).contains("请聚焦 JVM 垃圾回收细节");
    }

    @Test
    @DisplayName("动态追问明确禁止补造候选人未提及的实现前提")
    void forbidsInventedPremisesInFollowUpPrompts() {
      properties.setCriticEnabled(true);
      properties.setMaxReflexion(1);
      when(interviewer.nextQuestion(anyString(), anyString(), anyString(), anyString()))
          .thenReturn(new AgentQuestionOutput(
              "你刚才提到文档切块，实际处理了哪些资料，规则如何确定？",
              "承接候选人的切块实践", true));
      when(critic.review(anyString()))
          .thenReturn(new CriticVerdict(true, 90, "前提均来自上一答", ""));

      InterviewPlan plan = new InterviewPlan(
          List.of(new PlanTopic("RAG 文档处理", "上传与切块", 2)),
          "由浅入深", List.of(), List.of());
      NextQuestionRequest followUpRequest = new NextQuestionRequest(
          "session-1", 1L, PROVIDER, "java-backend", "mid",
          1, 8, plan, "我负责了文档上传和切块。", List.of("请介绍项目"), null, List.of());

      orchestrator.nextQuestion(followUpRequest);

      ArgumentCaptor<String> instructionCaptor = ArgumentCaptor.forClass(String.class);
      verify(interviewer).nextQuestion(anyString(), anyString(), anyString(), instructionCaptor.capture());
      assertThat(instructionCaptor.getValue())
          .contains("不得把泛称补成候选人没有说过的具体实现")
          .contains("上一答未说明亲历故障时必须使用条件式场景问法");

      ArgumentCaptor<String> reviewCaptor = ArgumentCaptor.forClass(String.class);
      verify(critic).review(reviewCaptor.capture());
      assertThat(reviewCaptor.getValue())
          .contains("上一答未明确出现且不能直接推出的前提，一律不通过")
          .contains("未说明亲历故障却要求讲真实故障案例，也一律不通过");
    }

    @Test
    @DisplayName("Reflexion 达上限短路：Critic 持续打回时采用最后一版，approved=false")
    void shortCircuitsAtReflexionLimit() {
      properties.setCriticEnabled(true);
      properties.setMaxReflexion(1);
      when(interviewer.nextQuestion(anyString(), anyString(), anyString(), anyString()))
          .thenReturn(output("Q1"), output("Q2"), output("Q3"));
      when(critic.review(anyString()))
          .thenReturn(new CriticVerdict(false, 30, "仍不达标", "继续改"));

      GeneratedQuestion result = orchestrator.nextQuestion(request());

      assertThat(result.reflexionRounds()).isEqualTo(1);
      assertThat(result.criticApproved()).isFalse();
      // maxReflexion=1 → 出题 maxReflexion+1=2 次后短路，不会无限循环
      verify(interviewer, times(2))
          .nextQuestion(anyString(), anyString(), anyString(), anyString());
      verify(critic, times(2)).review(anyString());
    }
  }

  @Nested
  @DisplayName("降级路径")
  class Fallback {

    @Test
    @DisplayName("Critic 关闭：Interviewer 直出，不调用 Critic")
    void criticDisabledDirectOutput() {
      properties.setCriticEnabled(false);
      properties.setMaxReflexion(2);
      when(interviewer.nextQuestion(anyString(), anyString(), anyString(), anyString()))
          .thenReturn(output("Q1"));

      GeneratedQuestion result = orchestrator.nextQuestion(request());

      assertThat(result.question()).isEqualTo("Q1");
      assertThat(result.reflexionRounds()).isZero();
      assertThat(result.criticApproved()).isTrue();
      verify(factory, never()).critic(any());
      verify(critic, never()).review(anyString());
    }

    @Test
    @DisplayName("Interviewer 彻底失败：回退到通用兜底题，approved=false 且不调用 Critic")
    void interviewerFailureFallsBack() {
      properties.setCriticEnabled(true);
      properties.setMaxReflexion(2);
      when(interviewer.nextQuestion(anyString(), anyString(), anyString(), anyString()))
          .thenReturn(null);

      GeneratedQuestion result = orchestrator.nextQuestion(request());

      assertThat(result.question()).isNotBlank();
      assertThat(result.criticApproved()).isFalse();
      assertThat(result.reflexionRounds()).isZero();
      verify(critic, never()).review(anyString());
    }
  }
}
