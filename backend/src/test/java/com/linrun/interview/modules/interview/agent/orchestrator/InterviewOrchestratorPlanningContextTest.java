package com.linrun.interview.modules.interview.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.ai.PromptSanitizer;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.interview.agent.AgentAiServiceFactory;
import com.linrun.interview.modules.interview.agent.AgentOrchestrationProperties;
import com.linrun.interview.modules.interview.agent.AgentTraceService;
import com.linrun.interview.modules.interview.agent.InterviewTurnDecisionService;
import com.linrun.interview.modules.interview.agent.PlannerAiService;
import com.linrun.interview.modules.interview.agent.model.InterviewPlan;
import com.linrun.interview.modules.interview.agent.model.InterviewPlan.PlanTopic;
import com.linrun.interview.modules.interview.memory.CandidateMemoryService;
import com.linrun.interview.modules.interview.service.InterviewKnowledgeRetrievalService;
import com.linrun.interview.modules.interview.topic.InterviewTopic;
import com.linrun.interview.modules.interview.topic.InterviewTopic.Category;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Planner 异步数据用户上下文")
class InterviewOrchestratorPlanningContextTest {

  @AfterEach
  void clearUserContext() {
    UserContext.clear();
  }

  @Test
  @DisplayName("消费线程无 UserContext 时按准备任务持久化 userId 检索知识库")
  void retrievesWithPersistedUserIdWithoutThreadContext() {
    KnowledgeBaseQueryService queryService = mock(KnowledgeBaseQueryService.class);
    InterviewKnowledgeRetrievalService retrievalService =
        new InterviewKnowledgeRetrievalService(queryService, mock(PromptSanitizer.class));
    AgentAiServiceFactory aiServiceFactory = mock(AgentAiServiceFactory.class);
    PlannerAiService planner = mock(PlannerAiService.class);
    when(aiServiceFactory.planner(7L)).thenReturn(planner);
    when(planner.plan(anyString())).thenReturn(new InterviewPlan(
        List.of(new PlanTopic("岗位技术", "验证技术深度", 5)),
        "由浅入深", List.of(), List.of()));
    when(queryService.retrieveContentsForInterviewEvidence(
        eq(7L), eq(List.of(9L)), anyString()))
        .thenReturn(List.of());

    AgentOrchestrationProperties properties = new AgentOrchestrationProperties();
    properties.setMetricsEnabled(false);
    CandidateMemoryService candidateMemoryService = mock(CandidateMemoryService.class);
    when(candidateMemoryService.buildMemorySection(7L, "java-backend")).thenReturn("");
    InterviewOrchestrator orchestrator = new InterviewOrchestrator(
        aiServiceFactory,
        properties,
        mock(AgentTraceService.class),
        candidateMemoryService,
        retrievalService,
        mock(InterviewTurnDecisionService.class),
        new ObjectMapper(),
        null);
    InterviewTopic topic = new InterviewTopic(
        "java-backend", "Java 后端", "岗位技术能力",
        List.of(new Category("spring", "Spring", "HIGH", "1.0.0")),
        true, null, "java-backend-v1", "1.0.0");

    UserContext.clear();
    orchestrator.plan(new InterviewOrchestrator.PlanRequest(
        "run-1", 7L, null, topic, "mid", 5, null, List.of(9L)));

    verify(queryService).retrieveContentsForInterviewEvidence(
        eq(7L), eq(List.of(9L)), anyString());
    verify(queryService, never()).retrieveContentsForInterviewEvidence(
        eq(List.of(9L)), anyString());
  }
}
