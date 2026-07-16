package com.linrun.interview.modules.interview.service;

import static org.mockito.Mockito.inOrder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.infrastructure.redis.InterviewSessionCache;
import com.linrun.interview.infrastructure.redis.RedisChatMemoryStore;
import com.linrun.interview.modules.interview.agent.AgentOrchestrationProperties;
import com.linrun.interview.modules.interview.agent.AgentTraceService;
import com.linrun.interview.modules.interview.agent.orchestrator.InterviewOrchestrator;
import com.linrun.interview.modules.interview.listener.EvaluateStreamProducer;
import com.linrun.interview.modules.interview.memory.CandidateMemoryService;
import com.linrun.interview.modules.interview.topic.InterviewTopicCatalog;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseListService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试会话删除测试")
class InterviewSessionServiceDeletionTest {

  @Mock
  private InterviewQuestionService questionService;
  @Mock
  private AnswerEvaluationService evaluationService;
  @Mock
  private InterviewPersistenceService persistenceService;
  @Mock
  private InterviewSessionCache sessionCache;
  @Mock
  private ObjectMapper objectMapper;
  @Mock
  private EvaluateStreamProducer evaluateStreamProducer;
  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private KnowledgeBaseListService knowledgeBaseListService;
  @Mock
  private InterviewTopicCatalog topicCatalog;
  @Mock
  private InterviewOrchestrator orchestrator;
  @Mock
  private AgentOrchestrationProperties agentProperties;
  @Mock
  private RedisChatMemoryStore chatMemoryStore;
  @Mock
  private CandidateMemoryService candidateMemoryService;
  @Mock
  private AgentTraceService agentTraceService;

  @InjectMocks
  private InterviewSessionService service;

  @Test
  @DisplayName("先由持久层按当前用户校验并删除，再清理缓存")
  void deletesPersistedSessionBeforeCacheWithoutRestoringQuestions() {
    service.deleteSession("job-session-1");

    InOrder order = inOrder(persistenceService, sessionCache);
    order.verify(persistenceService).deleteSessionBySessionId("job-session-1");
    order.verify(sessionCache).deleteSession("job-session-1");
  }
}
