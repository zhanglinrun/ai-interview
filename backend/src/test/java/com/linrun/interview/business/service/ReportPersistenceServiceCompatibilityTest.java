package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.mapper.JobInterviewSessionMapper;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;
import com.linrun.interview.business.constant.JobInterviewSessionStatus;
import com.linrun.interview.business.config.ReportGenerationProperties;
import com.linrun.interview.business.mapper.InterviewReportMapper;
import com.linrun.interview.business.entity.InterviewReportEntity;
import com.linrun.interview.business.constant.ReportStatus;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.type.EnumTypeHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("报告历史状态兼容")
class ReportPersistenceServiceCompatibilityTest {

  @Test
  @DisplayName("MyBatis 可以读取历史 EVALUATED 会话状态")
  void shouldMapLegacyEvaluatedStatus() throws Exception {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getString("status")).thenReturn("EVALUATED");

    JobInterviewSessionStatus status =
        new EnumTypeHandler<>(JobInterviewSessionStatus.class)
            .getResult(resultSet, "status");

    assertThat(status).isEqualTo(JobInterviewSessionStatus.EVALUATED);
    assertThat(status.completed()).isTrue();
    assertThat(status.terminal()).isTrue();
  }

  @Test
  @DisplayName("报告服务可以查询并展示历史 EVALUATED 会话的已有报告")
  void shouldReadExistingReportForLegacyEvaluatedSession() {
    InterviewReportMapper reportMapper = mock(InterviewReportMapper.class);
    JobInterviewSessionMapper sessionMapper = mock(JobInterviewSessionMapper.class);
    ReportFactAssembler factAssembler = mock(ReportFactAssembler.class);
    CapabilityProfileService profileService = mock(CapabilityProfileService.class);
    TrainingService trainingService = mock(TrainingService.class);
    ReportPersistenceService service = new ReportPersistenceService(
        reportMapper,
        sessionMapper,
        factAssembler,
        profileService,
        trainingService,
        new ObjectMapper(),
        new ReportGenerationProperties());

    JobInterviewSessionEntity session = JobInterviewSessionEntity.builder()
        .id(9L)
        .userId(7L)
        .sessionId("legacy-session")
        .preparationRunId("preparation-1")
        .status(JobInterviewSessionStatus.EVALUATED)
        .build();
    LocalDateTime createdAt = LocalDateTime.of(2026, 7, 15, 9, 0);
    InterviewReportEntity report = InterviewReportEntity.builder()
        .id(11L)
        .reportId("report-1")
        .userId(7L)
        .sessionId(9L)
        .status(ReportStatus.COMPLETED)
        .objectiveFactsJson("[]")
        .summaryJson("{\"overallFeedback\":\"完成\",\"strengths\":[],"
            + "\"improvements\":[\"历史模型建议\"]}")
        .gapsJson("[]")
        .generationAttempt(1)
        .createdAt(createdAt)
        .completedAt(createdAt.plusMinutes(1))
        .build();
    when(sessionMapper.selectOne(any())).thenReturn(session);
    when(sessionMapper.selectById(9L)).thenReturn(session);
    when(reportMapper.selectOne(any())).thenReturn(report);

    InterviewReportEntity found = service.findOwnedBySession(7L, "legacy-session")
        .orElseThrow();
    var view = service.toView(found);

    assertThat(view.reportId()).isEqualTo("report-1");
    assertThat(view.sessionId()).isEqualTo("legacy-session");
    assertThat(view.status()).isEqualTo(ReportStatus.COMPLETED);
    assertThat(view.summary().improvements()).isEmpty();
  }

  @Test
  @DisplayName("报告页可为没有报告的历史 EVALUATED 会话补建报告")
  void shouldCreateMissingReportForLegacyEvaluatedSession() {
    InterviewReportMapper reportMapper = mock(InterviewReportMapper.class);
    JobInterviewSessionMapper sessionMapper = mock(JobInterviewSessionMapper.class);
    ReportFactAssembler factAssembler = mock(ReportFactAssembler.class);
    ReportPersistenceService service = new ReportPersistenceService(
        reportMapper,
        sessionMapper,
        factAssembler,
        mock(CapabilityProfileService.class),
        mock(TrainingService.class),
        new ObjectMapper(),
        new ReportGenerationProperties());
    JobInterviewSessionEntity session = JobInterviewSessionEntity.builder()
        .id(9L)
        .userId(7L)
        .sessionId("legacy-session")
        .preparationRunId("preparation-1")
        .status(JobInterviewSessionStatus.EVALUATED)
        .build();
    when(sessionMapper.selectOne(any())).thenReturn(session);
    when(factAssembler.assemble(eq(session), anyString()))
        .thenReturn(new ReportFactAssembler.Assembly(List.of(), List.of()));
    when(reportMapper.insert(any(InterviewReportEntity.class))).thenReturn(1);

    InterviewReportEntity report = service.ensure(7L, "legacy-session");

    assertThat(report.getStatus()).isEqualTo(ReportStatus.GENERATING);
    assertThat(report.getSessionId()).isEqualTo(9L);
    assertThat(report.getUserId()).isEqualTo(7L);
  }
}
