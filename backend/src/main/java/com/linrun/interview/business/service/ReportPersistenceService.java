package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.business.mapper.JobInterviewSessionMapper;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;
import com.linrun.interview.business.constant.JobInterviewSessionStatus;
import com.linrun.interview.business.config.ReportGenerationProperties;
import com.linrun.interview.business.vo.ReportContracts.CapabilityGap;
import com.linrun.interview.business.vo.ReportContracts.ObjectiveFact;
import com.linrun.interview.business.vo.ReportContracts.ReportView;
import com.linrun.interview.business.vo.ReportContracts.SummaryContent;
import com.linrun.interview.business.mapper.InterviewReportMapper;
import com.linrun.interview.business.entity.CapabilityEvidenceEntity;
import com.linrun.interview.business.entity.InterviewReportEntity;
import com.linrun.interview.business.constant.ReportStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportPersistenceService {

  private final InterviewReportMapper reportMapper;
  private final JobInterviewSessionMapper sessionMapper;
  private final ReportFactAssembler factAssembler;
  private final CapabilityProfileService profileService;
  private final TrainingService trainingService;
  private final ObjectMapper objectMapper;
  private final ReportGenerationProperties generationProperties;

  @Transactional(rollbackFor = Exception.class)
  public InterviewReportEntity ensure(Long userId, String sessionId) {
    JobInterviewSessionEntity session = requireCompletedSession(userId, sessionId);
    InterviewReportEntity existing = findBySessionPk(session.getId()).orElse(null);
    if (existing != null) {
      return existing;
    }

    String reportId = UUID.randomUUID().toString();
    var assembly = factAssembler.assemble(session, reportId);
    InterviewReportEntity report = InterviewReportEntity.builder()
        .reportId(reportId)
        .userId(userId)
        .sessionId(session.getId())
        .status(ReportStatus.GENERATING)
        .objectiveFactsJson(writeJson(assembly.facts()))
        .objectiveReady(true)
        .summaryReady(false)
        .profileApplied(false)
        .generationAttempt(0)
        .createdAt(LocalDateTime.now())
        .updatedAt(LocalDateTime.now())
        .build();
    try {
      reportMapper.insert(report);
      return report;
    } catch (DuplicateKeyException duplicate) {
      return findBySessionPk(session.getId())
          .orElseThrow(() -> duplicate);
    }
  }

  public Optional<InterviewReportEntity> findOwnedBySession(Long userId, String sessionId) {
    JobInterviewSessionEntity session = sessionMapper.selectOne(
        Wrappers.<JobInterviewSessionEntity>lambdaQuery()
            .eq(JobInterviewSessionEntity::getUserId, userId)
            .eq(JobInterviewSessionEntity::getSessionId, sessionId)
            .isNotNull(JobInterviewSessionEntity::getPreparationRunId));
    return session == null ? Optional.empty() : findBySessionPk(session.getId());
  }

  public Optional<InterviewReportEntity> findInternal(String reportId) {
    return Optional.ofNullable(reportMapper.selectOne(
        Wrappers.<InterviewReportEntity>lambdaQuery()
            .eq(InterviewReportEntity::getReportId, reportId)));
  }

  public GenerationClaim claim(String reportId, Long userId) {
    LocalDateTime expiredBefore = LocalDateTime.now()
        .minus(generationProperties.getClaimLease());
    if (reportMapper.claimGeneration(reportId, userId, expiredBefore) > 0) {
      InterviewReportEntity claimed = reportMapper.selectOne(
          Wrappers.<InterviewReportEntity>lambdaQuery()
              .eq(InterviewReportEntity::getReportId, reportId)
              .eq(InterviewReportEntity::getUserId, userId));
      return claimed == null
          ? new GenerationClaim(GenerationClaimState.TERMINAL, null)
          : new GenerationClaim(GenerationClaimState.ACQUIRED, claimed);
    }
    InterviewReportEntity current = reportMapper.selectOne(
        Wrappers.<InterviewReportEntity>lambdaQuery()
            .eq(InterviewReportEntity::getReportId, reportId)
            .eq(InterviewReportEntity::getUserId, userId));
    if (current != null && current.getStatus() == ReportStatus.GENERATING) {
      return new GenerationClaim(GenerationClaimState.LEASE_HELD, current);
    }
    return new GenerationClaim(GenerationClaimState.TERMINAL, current);
  }

  public List<InterviewReportEntity> findRecoverableGenerations() {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime expiredBefore = now.minus(generationProperties.getClaimLease());
    LocalDateTime dispatchBefore = now.minus(generationProperties.getRecoveryGrace());
    int batchSize = Math.max(1, Math.min(generationProperties.getRecoveryBatchSize(), 200));
    return reportMapper.selectList(
        Wrappers.<InterviewReportEntity>lambdaQuery()
            .eq(InterviewReportEntity::getStatus, ReportStatus.GENERATING)
            .and(query -> query
                .lt(InterviewReportEntity::getGenerationClaimedAt, expiredBefore)
                .or(unclaimed -> unclaimed
                    .isNull(InterviewReportEntity::getGenerationClaimedAt)
                    .lt(InterviewReportEntity::getUpdatedAt, dispatchBefore)))
            .orderByAsc(InterviewReportEntity::getUpdatedAt)
            .last("LIMIT " + batchSize));
  }

  /**
   * 为恢复投递做数据库 CAS 预留。过期 claim 先释放；从未 claim 的任务按安静期触发。
   * 更新 updated_at 形成下一轮重投的节流窗口，进程若在预留后宕机，窗口过后仍会再次被扫描。
   */
  public boolean prepareRecoveryDispatch(String reportId, Long userId) {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime expiredBefore = now.minus(generationProperties.getClaimLease());
    if (reportMapper.releaseExpiredGenerationClaim(reportId, userId, expiredBefore) > 0) {
      return true;
    }
    LocalDateTime dispatchBefore = now.minus(generationProperties.getRecoveryGrace());
    return reportMapper.reserveUnclaimedGenerationDispatch(
        reportId, userId, dispatchBefore) > 0;
  }

  @Transactional(rollbackFor = Exception.class)
  public void complete(
      String reportId,
      Long userId,
      SummaryContent summary,
      List<CapabilityGap> gaps,
      List<CapabilityEvidenceEntity> evidence
  ) {
    InterviewReportEntity report = requireOwned(reportId, userId);
    if (report.getStatus() == ReportStatus.COMPLETED) {
      return;
    }
    if (report.getStatus() != ReportStatus.GENERATING) {
      throw new BusinessException(
          ErrorCode.INTERVIEW_REPORT_RETRY_NOT_ALLOWED, "复盘不在生成状态");
    }
    List<CapabilityGap> finalGaps = trainingService.ensureRecommended(userId, reportId, gaps);

    // 先把正式状态写入同一事务，再写能力证据与画像；任一环节失败会整体回滚。
    report.setStatus(ReportStatus.COMPLETED);
    report.setSummaryJson(writeJson(summary));
    report.setGapsJson(writeJson(finalGaps));
    report.setSummaryReady(true);
    report.setProfileApplied(false);
    report.setGenerationClaimedAt(null);
    report.setFailureCode(null);
    report.setFailureDetail(null);
    report.setUpdatedAt(LocalDateTime.now());
    report.setCompletedAt(LocalDateTime.now());
    reportMapper.updateById(report);

    profileService.insertAndRefresh(evidence);
    report.setProfileApplied(true);
    report.setUpdatedAt(LocalDateTime.now());
    reportMapper.updateById(report);
  }

  @Transactional(rollbackFor = Exception.class)
  public InterviewReportEntity resetForRetry(Long userId, String sessionId) {
    InterviewReportEntity report = findOwnedBySession(userId, sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_REPORT_NOT_FOUND));
    if (report.getStatus() != ReportStatus.FAILED || Boolean.TRUE.equals(report.getProfileApplied())) {
      throw new BusinessException(ErrorCode.INTERVIEW_REPORT_RETRY_NOT_ALLOWED);
    }
    report.setStatus(ReportStatus.GENERATING);
    report.setGenerationClaimedAt(null);
    report.setFailureCode(null);
    report.setFailureDetail(null);
    report.setUpdatedAt(LocalDateTime.now());
    reportMapper.updateById(report);
    return report;
  }

  @Transactional(rollbackFor = Exception.class)
  public void markFailed(String reportId, Long userId, String failureCode, String detail) {
    InterviewReportEntity report = requireOwned(reportId, userId);
    if (report.getStatus() == ReportStatus.COMPLETED) {
      return;
    }
    report.setStatus(ReportStatus.FAILED);
    report.setGenerationClaimedAt(null);
    report.setFailureCode(truncate(failureCode, 64));
    report.setFailureDetail(truncate(detail, 500));
    report.setUpdatedAt(LocalDateTime.now());
    reportMapper.updateById(report);
  }

  public ReportView toView(InterviewReportEntity report) {
    JobInterviewSessionEntity session = sessionMapper.selectById(report.getSessionId());
    if (session == null || !report.getUserId().equals(session.getUserId())) {
      throw new BusinessException(ErrorCode.INTERVIEW_REPORT_NOT_FOUND);
    }
    List<CapabilityGap> gaps = readJson(
        report.getGapsJson(), new TypeReference<List<CapabilityGap>>() {
        }, List.of());
    SummaryContent summary = alignLegacySummary(
        readJson(report.getSummaryJson(), new TypeReference<SummaryContent>() {
        }, null), gaps);
    return new ReportView(
        report.getReportId(), session.getSessionId(), report.getStatus(),
        readJson(report.getObjectiveFactsJson(), new TypeReference<List<ObjectiveFact>>() {
        }, List.of()),
        summary, gaps,
        report.getFailureCode(), report.getFailureDetail(), value(report.getGenerationAttempt()),
        report.getStatus() == ReportStatus.FAILED
            && !Boolean.TRUE.equals(report.getProfileApplied()),
        report.getCreatedAt(), report.getCompletedAt());
  }

  private SummaryContent alignLegacySummary(
      SummaryContent summary,
      List<CapabilityGap> gaps
  ) {
    if (summary == null) {
      return null;
    }
    List<String> improvements = gaps == null ? List.of() : gaps.stream()
        .map(gap -> gap.capabilityName() + "：" + gap.reason())
        .toList();
    return new SummaryContent(
        summary.overallFeedback(), summary.strengths(), improvements);
  }

  public List<ObjectiveFact> readFacts(InterviewReportEntity report) {
    return readJson(report.getObjectiveFactsJson(), new TypeReference<List<ObjectiveFact>>() {
    }, List.of());
  }

  public JobInterviewSessionEntity requireSessionForReport(InterviewReportEntity report) {
    JobInterviewSessionEntity session = sessionMapper.selectById(report.getSessionId());
    if (session == null || !report.getUserId().equals(session.getUserId())) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }
    return session;
  }

  private JobInterviewSessionEntity requireCompletedSession(Long userId, String sessionId) {
    JobInterviewSessionEntity session = sessionMapper.selectOne(
        Wrappers.<JobInterviewSessionEntity>lambdaQuery()
            .eq(JobInterviewSessionEntity::getUserId, userId)
            .eq(JobInterviewSessionEntity::getSessionId, sessionId)
            .isNotNull(JobInterviewSessionEntity::getPreparationRunId));
    if (session == null) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }
    if (session.getStatus() == null || !session.getStatus().completed()) {
      String message = session.getStatus() == JobInterviewSessionStatus.ABORTED
          ? "已中止场次只保留个人记录，不生成正式能力结论"
          : "岗位实战尚未完成，不能生成正式复盘";
      throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, message);
    }
    return session;
  }

  private InterviewReportEntity requireOwned(String reportId, Long userId) {
    InterviewReportEntity report = reportMapper.selectOne(
        Wrappers.<InterviewReportEntity>lambdaQuery()
            .eq(InterviewReportEntity::getReportId, reportId)
            .eq(InterviewReportEntity::getUserId, userId));
    if (report == null) {
      throw new BusinessException(ErrorCode.INTERVIEW_REPORT_NOT_FOUND);
    }
    return report;
  }

  private Optional<InterviewReportEntity> findBySessionPk(Long sessionPkId) {
    return Optional.ofNullable(reportMapper.selectOne(
        Wrappers.<InterviewReportEntity>lambdaQuery()
            .eq(InterviewReportEntity::getSessionId, sessionPkId)));
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "复盘数据序列化失败", e);
    }
  }

  private <T> T readJson(String json, TypeReference<T> type, T fallback) {
    if (json == null || json.isBlank()) {
      return fallback;
    }
    try {
      return objectMapper.readValue(json, type);
    } catch (Exception e) {
      return fallback;
    }
  }

  private int value(Integer value) {
    return value == null ? 0 : value;
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.strip();
    return normalized.length() <= maxLength
        ? normalized : normalized.substring(0, maxLength);
  }

  public enum GenerationClaimState {
    ACQUIRED,
    LEASE_HELD,
    TERMINAL
  }

  public record GenerationClaim(
      GenerationClaimState state,
      InterviewReportEntity report
  ) {
  }
}
