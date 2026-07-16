package com.linrun.interview.modules.jobinterview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.modules.jobinterview.config.JobInterviewProperties;
import com.linrun.interview.modules.jobinterview.model.InterviewSessionEventEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("岗位实战 SSE 断线回放")
class JobInterviewEventStreamServiceTest {

  @Mock
  private JobInterviewSessionPersistenceService sessionPersistence;

  @Test
  @DisplayName("应按 Last-Event-ID 之后的事件顺序回放且保持持久化事件 ID")
  void shouldReplayAfterEventId() {
    JobInterviewProperties properties = new JobInterviewProperties();
    properties.setReconnectEventLimit(100);
    JobInterviewEventStreamService service = new JobInterviewEventStreamService(
        sessionPersistence,
        new JobInterviewViewAssembler(new ObjectMapper().findAndRegisterModules()),
        properties);
    InterviewSessionEventEntity event = InterviewSessionEventEntity.builder()
        .id(12L).userId(7L).sessionId("session-1").eventType("QUESTION_ASKED")
        .sessionVersion(4L).payloadJson("{\"currentQuestionId\":21}")
        .createdAt(LocalDateTime.now()).build();
    when(sessionPersistence.listEvents(7L, "session-1", 11L, 100))
        .thenReturn(List.of(event));

    var replay = service.replay(7L, "session-1", 11L);

    assertThat(replay).hasSize(1);
    assertThat(replay.getFirst().eventId()).isEqualTo(12L);
    assertThat(replay.getFirst().payload()).containsEntry("currentQuestionId", 21);
    verify(sessionPersistence).requireOwned(7L, "session-1");
  }
}
