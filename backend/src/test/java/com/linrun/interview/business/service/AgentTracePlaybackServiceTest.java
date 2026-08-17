package com.linrun.interview.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.entity.AgentRunStepEntity;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.vo.AgentTraceCatalogItemDTO;
import com.linrun.interview.business.vo.AgentTracePlaybackDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 轨迹回放服务")
class AgentTracePlaybackServiceTest {

  @Mock
  private AgentTraceService agentTraceService;
  @Mock
  private InterviewPersistenceService persistenceService;

  @AfterEach
  void clearUser() {
    UserContext.clear();
  }

  @Test
  @DisplayName("目录优先列出有步骤的会话，孤立 run 排在后面")
  void catalogSortsByStepsAndKeepsOrphans() {
    UserContext.setUserId(7L);
    InterviewSessionEntity empty = session("empty-sess", InterviewSessionEntity.SessionStatus.CREATED, 6);
    InterviewSessionEntity text = session("text-sess", InterviewSessionEntity.SessionStatus.IN_PROGRESS, 8);
    when(persistenceService.findAll()).thenReturn(List.of(empty, text));
    when(agentTraceService.listByUser(7L)).thenReturn(List.of(
        step("text-sess", "interviewer", "ask"),
        step("text-sess", "critic", "critique"),
        step("orphan-run", "planner", "plan")));

    AgentTracePlaybackService service = newService();
    List<AgentTraceCatalogItemDTO> catalog = service.listCatalog();

    assertThat(catalog).extracting(AgentTraceCatalogItemDTO::sessionId)
        .containsExactly("text-sess", "orphan-run", "empty-sess");
    assertThat(catalog.get(0).stepCount()).isEqualTo(2);
    assertThat(catalog.get(1).orphanRun()).isTrue();
    assertThat(catalog.get(2).stepCount()).isEqualTo(0);
  }

  @Test
  @DisplayName("回放只按 sessionId 查步骤")
  void playbackLoadsSessionSources() {
    UserContext.setUserId(7L);
    InterviewSessionEntity text = session("text-sess", InterviewSessionEntity.SessionStatus.IN_PROGRESS, 8);
    text.setInterviewPlanJson("{\"topics\":[]}");
    when(persistenceService.findBySessionId("text-sess")).thenReturn(Optional.of(text));
    when(agentTraceService.listBySessionKeys(eq(7L), eq(List.of("text-sess"))))
        .thenReturn(List.of(step("text-sess", "planner", "plan")));

    AgentTracePlaybackDTO playback = newService().getPlayback("text-sess");

    assertThat(playback.sourceIds()).containsExactly("text-sess");
    assertThat(playback.agentMode()).isTrue();
    assertThat(playback.stepCount()).isEqualTo(1);
    assertThat(playback.spans()).hasSize(1);
    assertThat(playback.spans().getFirst().title()).isEqualTo("定大纲");
    assertThat(playback.spans().getFirst().children().getFirst().action()).isEqualTo("plan");
  }

  private AgentTracePlaybackService newService() {
    return new AgentTracePlaybackService(
        agentTraceService, persistenceService, new ObjectMapper());
  }

  private static InterviewSessionEntity session(String sessionId,
                                                InterviewSessionEntity.SessionStatus status,
                                                int total) {
    InterviewSessionEntity entity = new InterviewSessionEntity();
    entity.setSessionId(sessionId);
    entity.setStatus(status);
    entity.setTotalQuestions(total);
    entity.setCreatedAt(LocalDateTime.of(2026, 8, 16, 15, 0));
    return entity;
  }

  private static AgentRunStepEntity step(String sessionId, String role, String action) {
    return AgentRunStepEntity.builder()
        .id(1L)
        .sessionId(sessionId)
        .role(role)
        .action(action)
        .createdAt(LocalDateTime.of(2026, 8, 16, 15, 0))
        .build();
  }
}
