package com.linrun.interview.business.service;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.business.vo.ReportContracts.ReportView;
import com.linrun.interview.business.entity.InterviewReportEntity;
import com.linrun.interview.business.constant.ReportStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReportApplicationService {

  private final ReportPersistenceService persistenceService;
  private final ReportTaskPublisher taskPublisher;

  public ReportView getOrCreate(Long userId, String sessionId) {
    var existing = persistenceService.findOwnedBySession(userId, sessionId);
    boolean createdNow = existing.isEmpty();
    InterviewReportEntity report = existing
        .orElseGet(() -> persistenceService.ensure(userId, sessionId));
    if (report.getStatus() == ReportStatus.GENERATING
        && (createdNow
            || persistenceService.prepareRecoveryDispatch(report.getReportId(), userId))) {
      taskPublisher.publish(report.getReportId(), userId);
      report = persistenceService.findInternal(report.getReportId()).orElse(report);
    }
    return persistenceService.toView(report);
  }

  public ReportView getExisting(Long userId, String sessionId) {
    InterviewReportEntity report = persistenceService.findOwnedBySession(userId, sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_REPORT_NOT_FOUND));
    return persistenceService.toView(report);
  }

  public ReportView retry(Long userId, String sessionId) {
    InterviewReportEntity report = persistenceService.resetForRetry(userId, sessionId);
    taskPublisher.publish(report.getReportId(), userId);
    return persistenceService.toView(
        persistenceService.findInternal(report.getReportId()).orElse(report));
  }

  public void triggerCompleted(String sessionId, Long userId) {
    InterviewReportEntity report = persistenceService.ensure(userId, sessionId);
    if (report.getStatus() == ReportStatus.GENERATING
        && (report.getGenerationClaimedAt() == null
            || persistenceService.prepareRecoveryDispatch(report.getReportId(), userId))) {
      taskPublisher.publish(report.getReportId(), userId);
    }
  }
}
