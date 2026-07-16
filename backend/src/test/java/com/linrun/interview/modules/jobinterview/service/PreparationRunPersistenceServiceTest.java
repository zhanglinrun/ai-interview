package com.linrun.interview.modules.jobinterview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linrun.interview.modules.jobinterview.mapper.JobInterviewSessionMapper;
import com.linrun.interview.modules.jobinterview.mapper.PreparationRunMapper;
import com.linrun.interview.modules.jobinterview.model.JobInterviewSessionEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewSessionStatus;
import com.linrun.interview.modules.jobinterview.model.PreparationRunEntity;
import com.linrun.interview.modules.jobinterview.model.PreparationStatus;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("岗位实战准备任务复用边界")
class PreparationRunPersistenceServiceTest {

  @Mock
  private PreparationRunMapper mapper;
  @Mock
  private JobInterviewSessionMapper sessionMapper;

  private PreparationRunPersistenceService service;

  @BeforeEach
  void setUp() {
    MybatisConfiguration configuration = new MybatisConfiguration();
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(configuration, "preparation-run-test"),
        PreparationRunEntity.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(configuration, "job-interview-session-test"),
        JobInterviewSessionEntity.class);
    service = new PreparationRunPersistenceService(mapper, sessionMapper);
  }

  @Test
  @DisplayName("相同输入仍在准备时应复用任务，吸收重复点击")
  @SuppressWarnings("unchecked")
  void shouldReusePreparingRun() {
    PreparationRunEntity run = run(PreparationStatus.PREPARING, null);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(run);

    Optional<PreparationRunEntity> result = service.findReusable(7L, "fingerprint");

    assertThat(result).containsSame(run);
    verifyNoInteractions(sessionMapper);
  }

  @ParameterizedTest(name = "{0} 会话应允许恢复")
  @MethodSource("resumableStatuses")
  @DisplayName("只有仍可作答的会话才复用已完成准备任务")
  @SuppressWarnings("unchecked")
  void shouldReuseOnlyResumableSession(JobInterviewSessionStatus status) {
    PreparationRunEntity run = run(PreparationStatus.READY, "session-1");
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(run);
    when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session(status));

    Optional<PreparationRunEntity> result = service.findReusable(7L, "fingerprint");

    assertThat(result).containsSame(run);
  }

  @ParameterizedTest(name = "{0} 会话不得复用")
  @MethodSource("nonResumableStatuses")
  @DisplayName("已完成、已终止及其他不可续面状态必须创建新任务")
  @SuppressWarnings("unchecked")
  void shouldRejectNonResumableSession(JobInterviewSessionStatus status) {
    PreparationRunEntity run = run(PreparationStatus.READY, "session-1");
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(run);
    when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session(status));

    Optional<PreparationRunEntity> result = service.findReusable(7L, "fingerprint");

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("准备任务缺失关联会话时不得作为可恢复结果")
  @SuppressWarnings("unchecked")
  void shouldRejectMissingSession() {
    PreparationRunEntity run = run(PreparationStatus.READY, "session-1");
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(run);
    when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    assertThat(service.findReusable(7L, "fingerprint")).isEmpty();
  }

  private static Stream<JobInterviewSessionStatus> resumableStatuses() {
    return Stream.of(
        JobInterviewSessionStatus.READY,
        JobInterviewSessionStatus.IN_PROGRESS,
        JobInterviewSessionStatus.PAUSED);
  }

  private static Stream<JobInterviewSessionStatus> nonResumableStatuses() {
    return Stream.of(
        JobInterviewSessionStatus.COMPLETING,
        JobInterviewSessionStatus.COMPLETED,
        JobInterviewSessionStatus.EVALUATED,
        JobInterviewSessionStatus.ABORTED,
        JobInterviewSessionStatus.FAILED);
  }

  private PreparationRunEntity run(PreparationStatus status, String sessionId) {
    return PreparationRunEntity.builder()
        .runId("run-1")
        .userId(7L)
        .fingerprint("fingerprint")
        .status(status)
        .sessionId(sessionId)
        .build();
  }

  private JobInterviewSessionEntity session(JobInterviewSessionStatus status) {
    return JobInterviewSessionEntity.builder()
        .userId(7L)
        .sessionId("session-1")
        .preparationRunId("run-1")
        .status(status)
        .build();
  }
}
