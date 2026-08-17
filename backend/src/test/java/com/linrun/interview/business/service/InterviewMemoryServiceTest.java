package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.entity.InterviewSessionEntity.SessionStatus;
import com.linrun.interview.business.service.CandidateMemoryService.CandidateMemoryProfileDTO;
import com.linrun.interview.business.vo.InterviewMemoryView;
import com.linrun.interview.business.vo.InterviewQuestionDTO;
import com.linrun.interview.business.vo.TurnDecision.AnswerSignals;
import com.linrun.interview.infra.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("三层面试记忆")
class InterviewMemoryServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private InterviewPersistenceService persistenceService;
  private RedisChatMemoryStore chatMemoryStore;
  private CandidateMemoryService candidateMemoryService;
  private InterviewMemoryService service;

  @BeforeEach
  void setUp() {
    UserContext.setUserId(1L);
    persistenceService = mock(InterviewPersistenceService.class);
    chatMemoryStore = mock(RedisChatMemoryStore.class);
    candidateMemoryService = mock(CandidateMemoryService.class);
    AgentOrchestrationProperties properties = new AgentOrchestrationProperties();
    properties.setMemoryWindow(4);
    service = new InterviewMemoryService(
        persistenceService, chatMemoryStore, candidateMemoryService, properties, objectMapper);
  }

  @AfterEach
  void tearDown() {
    UserContext.clear();
  }

  @Test
  @DisplayName("进行中场次拆成短期原文、压缩摘要，并带上跨场长期记忆")
  void projectsThreeLayersFromLiveSession() throws Exception {
    InterviewQuestionDTO main = InterviewQuestionDTO.createAgent(
            0, "如何保证缓存一致性？", "template:java-backend:cache", "Redis",
            "Redis 缓存一致性", false, null, "template:java-backend:cache",
            "SWITCH_TOPIC", List.of("chunk:1"))
        .withAnswer("先更新数据库再删缓存，接受短暂不一致。")
        .withAnswerSignals(new AnswerSignals(24, true, false, true, false));
    InterviewSessionEntity live = session("session-live", "java-backend", SessionStatus.IN_PROGRESS, List.of(main));
    when(persistenceService.findAll()).thenReturn(List.of(live));
    when(chatMemoryStore.getMessages("session-live"))
        .thenReturn(List.of(mock(ChatMessage.class), mock(ChatMessage.class)));
    when(candidateMemoryService.getProfile(1L, null)).thenReturn(List.of(longTerm()));

    InterviewMemoryView view = service.getMemory(null);

    assertThat(view.shortTerm().live()).isTrue();
    assertThat(view.shortTerm().sessionId()).isEqualTo("session-live");
    assertThat(view.shortTerm().windowSize()).isEqualTo(4);
    assertThat(view.shortTerm().agentMessageCount()).isEqualTo(2);
    assertThat(view.shortTerm().turns()).extracting(turn -> turn.role())
        .containsExactly("ASSISTANT", "USER");
    assertThat(view.shortTerm().turns().get(1).text()).contains("先更新数据库");
    assertThat(view.compressed().turns()).hasSize(1);
    assertThat(view.compressed().turns().getFirst().topic()).isEqualTo("Redis 缓存一致性");
    assertThat(view.compressed().turns().getFirst().hasReasoning()).isTrue();
    assertThat(view.compressed().turns().getFirst().hasTradeOff()).isTrue();
    assertThat(view.longTerm()).hasSize(1);
    assertThat(view.longTerm().getFirst().masteryLevel()).isEqualTo("WEAKNESS");
    assertThat(view.longTerm().getFirst().verificationState()).isEqualTo("PROVISIONAL");
  }

  @Test
  @DisplayName("有进行中场次时不把已结束场次当成短期窗口")
  void prefersLiveSessionOverCompleted() throws Exception {
    InterviewQuestionDTO oldAnswer = InterviewQuestionDTO.createAgent(
            0, "旧题", "atom", "JVM", "JVM", false, null, "atom", "SWITCH_TOPIC", List.of())
        .withAnswer("旧回答");
    InterviewQuestionDTO liveAnswer = InterviewQuestionDTO.createAgent(
            0, "新题", "atom", "Redis", "Redis", false, null, "atom", "SWITCH_TOPIC", List.of())
        .withAnswer("新回答");
    InterviewSessionEntity completed = session(
        "session-old", "java-backend", SessionStatus.EVALUATED, List.of(oldAnswer));
    InterviewSessionEntity live = session(
        "session-new", "java-backend", SessionStatus.IN_PROGRESS, List.of(liveAnswer));
    when(persistenceService.findAll()).thenReturn(List.of(completed, live));
    when(chatMemoryStore.getMessages(any())).thenReturn(List.of());
    when(candidateMemoryService.getProfile(1L, "java-backend")).thenReturn(List.of());

    InterviewMemoryView view = service.getMemory("java-backend");

    assertThat(view.shortTerm().sessionId()).isEqualTo("session-new");
    assertThat(view.compressed().sessionId()).isEqualTo("session-new");
    assertThat(view.shortTerm().turns().get(1).text()).isEqualTo("新回答");
  }

  @Test
  @DisplayName("没有场次时三层都为空，仍返回窗口配置")
  void returnsEmptyLayersWithoutSessions() {
    when(persistenceService.findAll()).thenReturn(List.of());
    when(candidateMemoryService.getProfile(1L, null)).thenReturn(List.of());

    InterviewMemoryView view = service.getMemory(null);

    assertThat(view.shortTerm().turns()).isEmpty();
    assertThat(view.shortTerm().windowSize()).isEqualTo(4);
    assertThat(view.compressed().turns()).isEmpty();
    assertThat(view.longTerm()).isEmpty();
  }

  private InterviewSessionEntity session(
      String sessionId, String skillId, SessionStatus status, List<InterviewQuestionDTO> questions)
      throws Exception {
    InterviewSessionEntity entity = new InterviewSessionEntity();
    entity.setSessionId(sessionId);
    entity.setUserId(1L);
    entity.setSkillId(skillId);
    entity.setStatus(status);
    entity.setQuestionsJson(objectMapper.writeValueAsString(questions));
    return entity;
  }

  private CandidateMemoryProfileDTO longTerm() {
    return new CandidateMemoryProfileDTO(
        "template:java-backend:cache",
        "Redis 缓存一致性",
        58,
        2,
        1,
        "WEAKNESS",
        "PROVISIONAL",
        "LOW",
        2,
        0,
        0,
        "weakness",
        "没讲清楚失败窗口。",
        List.of("chunk:1"),
        "session-old",
        LocalDateTime.now());
  }
}
