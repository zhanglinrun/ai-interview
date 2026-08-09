package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;
import com.linrun.interview.business.constant.JobInterviewSessionStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("岗位实战会话恢复语义")
class JobInterviewViewAssemblerTest {

  private final JobInterviewViewAssembler assembler = new JobInterviewViewAssembler(
      new ObjectMapper().findAndRegisterModules());

  @Test
  @DisplayName("暂停且尚未恢复过时应明确允许继续")
  void shouldExposeResumeAvailabilityBeforeLimit() {
    JobInterviewSessionEntity session = pausedSession(0);

    assertThat(assembler.session(session, null, 0, 1).canResume()).isTrue();
  }

  @Test
  @DisplayName("已经恢复过一次后再次暂停应明确禁止继续")
  void shouldExposeResumeLimitAfterSecondPause() {
    JobInterviewSessionEntity session = pausedSession(1);

    assertThat(assembler.session(session, null, 0, 1).canResume()).isFalse();
  }

  private JobInterviewSessionEntity pausedSession(int continuationCount) {
    return JobInterviewSessionEntity.builder()
        .sessionId("session-1")
        .status(JobInterviewSessionStatus.PAUSED)
        .sessionVersion(3L)
        .continuationCount(continuationCount)
        .resumeExpiresAt(LocalDateTime.now().plusHours(1))
        .degradedReasonsJson("[]")
        .build();
  }
}
