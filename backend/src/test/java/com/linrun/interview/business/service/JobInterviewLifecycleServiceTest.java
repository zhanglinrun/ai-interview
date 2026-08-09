package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.config.JobInterviewProperties;
import com.linrun.interview.business.mapper.InterviewCommandMapper;
import com.linrun.interview.business.mapper.InterviewSessionEventMapper;
import com.linrun.interview.business.mapper.JobInterviewSessionMapper;
import com.linrun.interview.business.entity.InterviewCommandEntity;
import com.linrun.interview.business.constant.InterviewCommandStatus;
import com.linrun.interview.business.constant.InterviewCommandType;
import com.linrun.interview.business.entity.InterviewSessionEventEntity;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;
import com.linrun.interview.business.constant.JobInterviewSessionStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

@ExtendWith(MockitoExtension.class)
@DisplayName("岗位实战暂停与续面窗口")
class JobInterviewLifecycleServiceTest {

  @Mock
  private JobInterviewSessionMapper sessionMapper;
  @Mock
  private InterviewCommandMapper commandMapper;
  @Mock
  private InterviewSessionEventMapper eventMapper;
  @Mock
  private JobInterviewSessionPersistenceService sessionPersistence;

  private JobInterviewLifecycleService service;

  @BeforeEach
  void setUp() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "job-interview-lifecycle-test"),
        JobInterviewSessionEntity.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "job-interview-command-lease-test"),
        InterviewCommandEntity.class);
    JobInterviewProperties properties = new JobInterviewProperties();
    properties.setCommandLeaseSeconds(60);
    properties.setIdlePauseMinutes(30);
    properties.setResumeHours(24);
    service = new JobInterviewLifecycleService(
        sessionMapper, commandMapper, eventMapper, sessionPersistence, properties,
        new ObjectMapper().findAndRegisterModules());
  }

  @Test
  @DisplayName("进程宕机遗留的过期 PROCESSING 指令应回收且不推进会话版本")
  void shouldReclaimStaleProcessingCommandAfterCrashWindow() {
    JobInterviewSessionEntity active = session(JobInterviewSessionStatus.IN_PROGRESS, 3L);
    active.setActiveCommandId("cmd-stale");
    InterviewCommandEntity stale = command(
        "cmd-stale", 3L, LocalDateTime.now().minusMinutes(2));
    when(sessionPersistence.requireOwned(7L, "session-1"))
        .thenReturn(active, active);
    when(commandMapper.selectOne(any(Wrapper.class))).thenReturn(stale);
    when(sessionMapper.releaseCommand(11L, 7L, 3L, "cmd-stale")).thenReturn(1);
    when(commandMapper.failStaleProcessingCommand(
        eq(31L), eq(7L), eq("session-1"), eq("cmd-stale"), eq(3L),
        any(LocalDateTime.class), eq("COMMAND_LEASE_EXPIRED"),
        any(String.class), any(LocalDateTime.class))).thenReturn(1);

    JobInterviewSessionEntity result = service.reconcileOwned(7L, "session-1");

    assertThat(result.getActiveCommandId()).isNull();
    assertThat(result.getSessionVersion()).isEqualTo(3L);
    verify(sessionMapper).releaseCommand(11L, 7L, 3L, "cmd-stale");
    verify(commandMapper).failStaleProcessingCommand(
        eq(31L), eq(7L), eq("session-1"), eq("cmd-stale"), eq(3L),
        any(LocalDateTime.class), eq("COMMAND_LEASE_EXPIRED"),
        any(String.class), any(LocalDateTime.class));
  }

  @Test
  @DisplayName("租约内的正常并发指令不得被生命周期请求误释放")
  void shouldKeepFreshProcessingCommandExclusive() {
    JobInterviewSessionEntity active = session(JobInterviewSessionStatus.IN_PROGRESS, 3L);
    active.setActiveCommandId("cmd-fresh");
    InterviewCommandEntity fresh = command(
        "cmd-fresh", 3L, LocalDateTime.now().minusSeconds(10));
    when(sessionPersistence.requireOwned(7L, "session-1"))
        .thenReturn(active, active);
    when(commandMapper.selectOne(any(Wrapper.class))).thenReturn(fresh);

    JobInterviewSessionEntity result = service.reconcileOwned(7L, "session-1");

    assertThat(result.getActiveCommandId()).isEqualTo("cmd-fresh");
    assertThat(result.getSessionVersion()).isEqualTo(3L);
    verify(sessionMapper, never()).releaseCommand(any(), any(), any(), any());
    verify(commandMapper, never()).failStaleProcessingCommand(
        any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("空闲超过阈值应通过乐观更新暂停并生成可回放事件")
  @SuppressWarnings("unchecked")
  void shouldPauseIdleSession() {
    JobInterviewSessionEntity active = session(JobInterviewSessionStatus.IN_PROGRESS, 3L);
    active.setLastActivityAt(LocalDateTime.now().minusMinutes(31));
    JobInterviewSessionEntity paused = session(JobInterviewSessionStatus.PAUSED, 4L);
    paused.setResumeExpiresAt(LocalDateTime.now().plusHours(24));
    when(sessionPersistence.requireOwned(7L, "session-1"))
        .thenReturn(active, paused);
    when(sessionMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);

    JobInterviewSessionEntity result = service.reconcileOwned(7L, "session-1");

    assertThat(result.getStatus()).isEqualTo(JobInterviewSessionStatus.PAUSED);
    verify(eventMapper).insert(any(InterviewSessionEventEntity.class));
  }

  @Test
  @DisplayName("暂停超过 24 小时应收敛到 ABORTED 且不伪造完成")
  @SuppressWarnings("unchecked")
  void shouldAbortExpiredResumeWindow() {
    JobInterviewSessionEntity paused = session(JobInterviewSessionStatus.PAUSED, 4L);
    paused.setResumeExpiresAt(LocalDateTime.now().minusSeconds(1));
    JobInterviewSessionEntity aborted = session(JobInterviewSessionStatus.ABORTED, 5L);
    when(sessionPersistence.requireOwned(7L, "session-1"))
        .thenReturn(paused, aborted);
    when(sessionMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);

    JobInterviewSessionEntity result = service.reconcileOwned(7L, "session-1");

    assertThat(result.getStatus()).isEqualTo(JobInterviewSessionStatus.ABORTED);
    assertThat(result.getStatus()).isNotEqualTo(JobInterviewSessionStatus.COMPLETED);
    verify(eventMapper).insert(any(InterviewSessionEventEntity.class));
  }

  private JobInterviewSessionEntity session(JobInterviewSessionStatus status, long version) {
    return JobInterviewSessionEntity.builder()
        .id(11L).userId(7L).sessionId("session-1").preparationRunId("run-1")
        .status(status).sessionVersion(version).lastActivityAt(LocalDateTime.now())
        .build();
  }

  private InterviewCommandEntity command(
      String commandId,
      long expectedVersion,
      LocalDateTime updatedAt
  ) {
    return InterviewCommandEntity.builder()
        .id(31L).userId(7L).sessionId("session-1").commandId(commandId)
        .commandType(InterviewCommandType.SUBMIT_ANSWER)
        .expectedSessionVersion(expectedVersion)
        .status(InterviewCommandStatus.PROCESSING)
        .createdAt(updatedAt).updatedAt(updatedAt)
        .build();
  }
}
