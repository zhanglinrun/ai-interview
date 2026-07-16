package com.linrun.interview.modules.interview.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.infrastructure.redis.InterviewSessionCache;
import com.linrun.interview.infrastructure.redis.RedisService;
import com.linrun.interview.modules.interview.mapper.InterviewSessionMapper;
import com.linrun.interview.modules.interview.memory.CandidateMemoryService;
import com.linrun.interview.modules.interview.model.InterviewQuestionDTO;
import com.linrun.interview.modules.interview.model.InterviewReportDTO;
import com.linrun.interview.modules.interview.model.InterviewSessionEntity;
import com.linrun.interview.modules.interview.model.InterviewSessionDTO.SessionStatus;
import com.linrun.interview.modules.interview.service.AnswerEvaluationService;
import com.linrun.interview.modules.interview.service.InterviewPersistenceService;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("面试评估消费者测试")
class EvaluateStreamConsumerTest {

  @Test
  @DisplayName("报告已落库时从持久化数据恢复能力观测且不重复调用 LLM")
  void recoversCapabilityObservationsFromStoredReport() throws Exception {
    Fixture fixture = fixture();
    InterviewQuestionDTO question = question();
    InterviewReportDTO report = report(question);
    InterviewSessionEntity session = session(
        InterviewSessionEntity.SessionStatus.EVALUATED,
        fixture.objectMapper.writeValueAsString(List.of(question)));
    when(fixture.persistenceService.findBySessionIdInternal("session-1"))
        .thenReturn(Optional.of(session));
    when(fixture.persistenceService.loadStoredReportInternal("session-1"))
        .thenReturn(Optional.of(report));

    fixture.consumer.processBusiness(new EvaluateStreamConsumer.EvaluatePayload("session-1", 1L));

    verify(fixture.candidateMemoryService)
        .extractAndSave(eq(session), eq(report), eq(List.of(question)));
    verify(fixture.sessionCache).updateSessionStatus("session-1", SessionStatus.EVALUATED);
    verify(fixture.llmProviderRegistry, never()).getUserChatModel(any());
    verify(fixture.evaluationService, never())
        .evaluateInterview(any(), any(), any(), any());
  }

  @Test
  @DisplayName("报告保存后能力观测写入失败会向上抛出以触发 MQ 重试")
  void retriesWhenCapabilityObservationPersistenceFails() throws Exception {
    Fixture fixture = fixture();
    InterviewQuestionDTO question = question();
    InterviewReportDTO report = report(question);
    InterviewSessionEntity session = session(
        InterviewSessionEntity.SessionStatus.COMPLETED,
        fixture.objectMapper.writeValueAsString(List.of(question)));
    ChatModel chatModel = mock(ChatModel.class);
    DataAccessResourceFailureException failure =
        new DataAccessResourceFailureException("database unavailable");
    when(fixture.persistenceService.findBySessionIdInternal("session-1"))
        .thenReturn(Optional.of(session));
    when(fixture.persistenceService.findAnswersBySessionId("session-1"))
        .thenReturn(List.of());
    when(fixture.llmProviderRegistry.getUserChatModel(1L)).thenReturn(chatModel);
    when(fixture.evaluationService.evaluateInterview(
        eq(chatModel), eq("session-1"), eq(""), any()))
        .thenReturn(report);
    org.mockito.Mockito.doThrow(failure).when(fixture.candidateMemoryService)
        .extractAndSave(eq(session), eq(report), any());

    assertThatThrownBy(() -> fixture.consumer.processBusiness(
        new EvaluateStreamConsumer.EvaluatePayload("session-1", 1L)))
        .isSameAs(failure);

    InOrder order = inOrder(fixture.persistenceService, fixture.candidateMemoryService);
    order.verify(fixture.persistenceService).saveReport("session-1", report);
    order.verify(fixture.candidateMemoryService)
        .extractAndSave(eq(session), eq(report), any());
  }

  private Fixture fixture() {
    RedisService redisService = mock(RedisService.class);
    InterviewSessionMapper sessionMapper = mock(InterviewSessionMapper.class);
    AnswerEvaluationService evaluationService = mock(AnswerEvaluationService.class);
    InterviewPersistenceService persistenceService = mock(InterviewPersistenceService.class);
    LlmProviderRegistry llmProviderRegistry = mock(LlmProviderRegistry.class);
    CandidateMemoryService candidateMemoryService = mock(CandidateMemoryService.class);
    InterviewSessionCache sessionCache = mock(InterviewSessionCache.class);
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    EvaluateStreamConsumer consumer = new EvaluateStreamConsumer(
        redisService, sessionMapper, evaluationService, persistenceService, objectMapper,
        llmProviderRegistry, candidateMemoryService, sessionCache);
    return new Fixture(consumer, evaluationService, persistenceService, objectMapper,
        llmProviderRegistry, candidateMemoryService, sessionCache);
  }

  private InterviewSessionEntity session(InterviewSessionEntity.SessionStatus status,
                                           String questionsJson) {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("session-1");
    session.setUserId(1L);
    session.setSkillId("java-backend");
    session.setStatus(status);
    session.setQuestionsJson(questionsJson);
    return session;
  }

  private InterviewQuestionDTO question() {
    return InterviewQuestionDTO.createAgent(
        0, "如何保证缓存一致性？", "template:java-backend:redis", "Redis",
        "Redis", false, null, "template:java-backend:redis", "SWITCH_TOPIC",
        List.of("chunk:101"));
  }

  private InterviewReportDTO report(InterviewQuestionDTO question) {
    InterviewReportDTO.QuestionEvaluation evaluation = new InterviewReportDTO.QuestionEvaluation(
        0, question.question(), question.category(), "先更新数据库再删缓存，因为……",
        82, "能说明失败窗口与工程取舍。");
    return new InterviewReportDTO(
        "session-1", 1, 82, List.of(), List.of(evaluation),
        "总体评价", List.of(), List.of(), List.of());
  }

  private record Fixture(
      EvaluateStreamConsumer consumer,
      AnswerEvaluationService evaluationService,
      InterviewPersistenceService persistenceService,
      ObjectMapper objectMapper,
      LlmProviderRegistry llmProviderRegistry,
      CandidateMemoryService candidateMemoryService,
      InterviewSessionCache sessionCache
  ) {}
}
