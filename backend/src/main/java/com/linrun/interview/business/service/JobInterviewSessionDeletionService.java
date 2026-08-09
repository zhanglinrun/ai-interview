package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.business.mapper.CodingAttemptMapper;
import com.linrun.interview.business.mapper.CodingDraftMapper;
import com.linrun.interview.business.mapper.JudgeSubmissionMapper;
import com.linrun.interview.business.entity.CodingAttemptEntity;
import com.linrun.interview.business.constant.CodingAttemptMode;
import com.linrun.interview.business.entity.CodingDraftEntity;
import com.linrun.interview.business.entity.JudgeSubmissionEntity;
import com.linrun.interview.business.entity.AgentRunStepEntity;
import com.linrun.interview.business.entity.AgentRunEntity;
import com.linrun.interview.business.mapper.AgentRunMapper;
import com.linrun.interview.business.mapper.AgentRunStepMapper;
import com.linrun.interview.business.mapper.CandidateMemoryMapper;
import com.linrun.interview.business.service.CandidateMemoryEntity;
import com.linrun.interview.business.mapper.InterviewCodeDraftMapper;
import com.linrun.interview.business.mapper.InterviewCommandMapper;
import com.linrun.interview.business.mapper.InterviewSessionEventMapper;
import com.linrun.interview.business.mapper.JobInterviewAnswerMapper;
import com.linrun.interview.business.mapper.JobInterviewQuestionMapper;
import com.linrun.interview.business.mapper.PreparationRunMapper;
import com.linrun.interview.business.entity.InterviewCodeDraftEntity;
import com.linrun.interview.business.entity.InterviewCommandEntity;
import com.linrun.interview.business.entity.InterviewSessionEventEntity;
import com.linrun.interview.business.entity.JobInterviewAnswerEntity;
import com.linrun.interview.business.entity.JobInterviewQuestionEntity;
import com.linrun.interview.business.entity.PreparationRunEntity;
import com.linrun.interview.rag.mapper.EvidenceSnapshotMapper;
import com.linrun.interview.rag.mapper.EvidenceSnapshotRefMapper;
import com.linrun.interview.rag.model.EvidenceSnapshotEntity;
import com.linrun.interview.rag.model.EvidenceSnapshotRefEntity;
import com.linrun.interview.business.mapper.CapabilityEvidenceMapper;
import com.linrun.interview.business.mapper.InterviewReportMapper;
import com.linrun.interview.business.mapper.LlmUsageRecordMapper;
import com.linrun.interview.business.mapper.TrainingTaskMapper;
import com.linrun.interview.business.entity.CapabilityEvidenceEntity;
import com.linrun.interview.business.entity.InterviewReportEntity;
import com.linrun.interview.business.entity.LlmUsageRecordEntity;
import com.linrun.interview.business.entity.TrainingTaskEntity;
import com.linrun.interview.business.service.CapabilityProfileService;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 删除面试会话的岗位实战扩展数据。
 *
 * <p>原始回答、源码、证据快照和回放数据随会话删除；跨会话训练成绩与能力证据是用户的
 * 长期学习历史，保留内容但解除 session/report/question 溯源，避免形成悬空引用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobInterviewSessionDeletionService {

  private static final String PREPARATION_EVIDENCE_CONTEXT = "JOB_INTERVIEW_PREPARATION";

  private final InterviewCodeDraftMapper interviewCodeDraftMapper;
  private final JobInterviewAnswerMapper answerMapper;
  private final JobInterviewQuestionMapper questionMapper;
  private final InterviewCommandMapper commandMapper;
  private final InterviewSessionEventMapper eventMapper;
  private final PreparationRunMapper preparationRunMapper;
  private final InterviewReportMapper reportMapper;
  private final CapabilityEvidenceMapper capabilityEvidenceMapper;
  private final TrainingTaskMapper trainingTaskMapper;
  private final LlmUsageRecordMapper usageRecordMapper;
  private final AgentRunStepMapper agentRunStepMapper;
  private final AgentRunMapper agentRunMapper;
  private final CandidateMemoryMapper candidateMemoryMapper;
  private final CodingAttemptMapper codingAttemptMapper;
  private final CodingDraftMapper codingDraftMapper;
  private final JudgeSubmissionMapper judgeSubmissionMapper;
  private final EvidenceSnapshotMapper evidenceSnapshotMapper;
  private final EvidenceSnapshotRefMapper evidenceSnapshotRefMapper;
  private final CapabilityProfileService capabilityProfileService;

  /** 调用方在同一事务内完成主会话删除；所有条件同时包含用户和会话标识。 */
  @Transactional(rollbackFor = Exception.class)
  public void deleteOwnedSessionArtifacts(Long userId, Long sessionPkId, String sessionId) {
    validate(userId, sessionPkId, sessionId);

    List<String> reportIds = findReportIds(userId, sessionPkId);
    List<String> affectedAtoms = findAffectedCapabilityAtoms(userId, sessionPkId, reportIds);
    List<PreparationRunEntity> preparations = findPreparations(userId, sessionId);

    detachLongTermLearningHistory(userId, sessionPkId, sessionId, reportIds);
    deleteReports(userId, sessionPkId);
    deleteAlgorithmArtifacts(userId, sessionId);
    deleteInterviewRuntimeArtifacts(userId, sessionPkId, sessionId);
    deletePreparationArtifacts(userId, sessionId, preparations);

    affectedAtoms.forEach(atomId -> capabilityProfileService.refresh(userId, atomId));
    log.info(
        "面试会话扩展数据已删除，长期学习投影已解除溯源: userId={}, sessionId={}, reports={}, atoms={}",
        userId, sessionId, reportIds.size(), affectedAtoms.size());
  }

  private List<String> findReportIds(Long userId, Long sessionPkId) {
    return reportMapper.selectList(Wrappers.<InterviewReportEntity>lambdaQuery()
            .eq(InterviewReportEntity::getUserId, userId)
            .eq(InterviewReportEntity::getSessionId, sessionPkId))
        .stream()
        .map(InterviewReportEntity::getReportId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  private List<String> findAffectedCapabilityAtoms(
      Long userId,
      Long sessionPkId,
      List<String> reportIds
  ) {
    var query = Wrappers.<CapabilityEvidenceEntity>lambdaQuery()
        .eq(CapabilityEvidenceEntity::getUserId, userId)
        .and(scope -> {
          scope.eq(CapabilityEvidenceEntity::getSessionId, sessionPkId);
          if (!reportIds.isEmpty()) {
            scope.or().in(CapabilityEvidenceEntity::getReportId, reportIds);
          }
        });
    return capabilityEvidenceMapper.selectList(query).stream()
        .map(CapabilityEvidenceEntity::getCapabilityAtomId)
        .filter(value -> value != null && !value.isBlank())
        .distinct()
        .toList();
  }

  private List<PreparationRunEntity> findPreparations(Long userId, String sessionId) {
    return preparationRunMapper.selectList(Wrappers.<PreparationRunEntity>lambdaQuery()
        .eq(PreparationRunEntity::getUserId, userId)
        .eq(PreparationRunEntity::getSessionId, sessionId));
  }

  private void detachLongTermLearningHistory(
      Long userId,
      Long sessionPkId,
      String sessionId,
      List<String> reportIds
  ) {
    if (!reportIds.isEmpty()) {
      trainingTaskMapper.update(null, Wrappers.<TrainingTaskEntity>lambdaUpdate()
          .eq(TrainingTaskEntity::getUserId, userId)
          .in(TrainingTaskEntity::getReportId, reportIds)
          .set(TrainingTaskEntity::getReportId, null)
          .set(TrainingTaskEntity::getSourceQuestionId, null));
    }

    var evidenceUpdate = Wrappers.<CapabilityEvidenceEntity>lambdaUpdate()
        .eq(CapabilityEvidenceEntity::getUserId, userId)
        .and(scope -> {
          scope.eq(CapabilityEvidenceEntity::getSessionId, sessionPkId);
          if (!reportIds.isEmpty()) {
            scope.or().in(CapabilityEvidenceEntity::getReportId, reportIds);
          }
        })
        .set(CapabilityEvidenceEntity::getReportId, null)
        .set(CapabilityEvidenceEntity::getSessionId, null)
        .set(CapabilityEvidenceEntity::getQuestionId, null);
    capabilityEvidenceMapper.update(null, evidenceUpdate);

    var usageUpdate = Wrappers.<LlmUsageRecordEntity>lambdaUpdate()
        .eq(LlmUsageRecordEntity::getUserId, userId)
        .and(scope -> {
          scope.eq(LlmUsageRecordEntity::getSessionId, sessionId);
          if (!reportIds.isEmpty()) {
            scope.or().in(LlmUsageRecordEntity::getReportId, reportIds);
          }
        })
        .set(LlmUsageRecordEntity::getSessionId, null)
        .set(LlmUsageRecordEntity::getReportId, null);
    usageRecordMapper.update(null, usageUpdate);

    candidateMemoryMapper.update(null, Wrappers.<CandidateMemoryEntity>lambdaUpdate()
        .eq(CandidateMemoryEntity::getUserId, userId)
        .eq(CandidateMemoryEntity::getSessionId, sessionId)
        .set(CandidateMemoryEntity::getSessionId, null));
  }

  private void deleteReports(Long userId, Long sessionPkId) {
    reportMapper.delete(Wrappers.<InterviewReportEntity>lambdaQuery()
        .eq(InterviewReportEntity::getUserId, userId)
        .eq(InterviewReportEntity::getSessionId, sessionPkId));
  }

  private void deleteAlgorithmArtifacts(Long userId, String sessionId) {
    List<Long> attemptIds = codingAttemptMapper.selectList(
            Wrappers.<CodingAttemptEntity>lambdaQuery()
                .select(CodingAttemptEntity::getId)
                .eq(CodingAttemptEntity::getUserId, userId)
                .eq(CodingAttemptEntity::getMode, CodingAttemptMode.JOB_INTERVIEW)
                .likeRight(CodingAttemptEntity::getContextId, sessionId + ":"))
        .stream()
        .map(CodingAttemptEntity::getId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    if (attemptIds.isEmpty()) {
      return;
    }
    judgeSubmissionMapper.delete(Wrappers.<JudgeSubmissionEntity>lambdaQuery()
        .eq(JudgeSubmissionEntity::getUserId, userId)
        .in(JudgeSubmissionEntity::getAttemptId, attemptIds));
    codingDraftMapper.delete(Wrappers.<CodingDraftEntity>lambdaQuery()
        .eq(CodingDraftEntity::getUserId, userId)
        .in(CodingDraftEntity::getAttemptId, attemptIds));
    codingAttemptMapper.delete(Wrappers.<CodingAttemptEntity>lambdaQuery()
        .eq(CodingAttemptEntity::getUserId, userId)
        .eq(CodingAttemptEntity::getMode, CodingAttemptMode.JOB_INTERVIEW)
        .in(CodingAttemptEntity::getId, attemptIds));
  }

  private void deleteInterviewRuntimeArtifacts(
      Long userId,
      Long sessionPkId,
      String sessionId
  ) {
    interviewCodeDraftMapper.delete(Wrappers.<InterviewCodeDraftEntity>lambdaQuery()
        .eq(InterviewCodeDraftEntity::getUserId, userId)
        .eq(InterviewCodeDraftEntity::getSessionId, sessionPkId));
    answerMapper.delete(Wrappers.<JobInterviewAnswerEntity>lambdaQuery()
        .eq(JobInterviewAnswerEntity::getUserId, userId)
        .eq(JobInterviewAnswerEntity::getSessionId, sessionPkId));
    questionMapper.delete(Wrappers.<JobInterviewQuestionEntity>lambdaQuery()
        .eq(JobInterviewQuestionEntity::getUserId, userId)
        .eq(JobInterviewQuestionEntity::getSessionId, sessionPkId));
    commandMapper.delete(Wrappers.<InterviewCommandEntity>lambdaQuery()
        .eq(InterviewCommandEntity::getUserId, userId)
        .eq(InterviewCommandEntity::getSessionId, sessionId));
    eventMapper.delete(Wrappers.<InterviewSessionEventEntity>lambdaQuery()
        .eq(InterviewSessionEventEntity::getUserId, userId)
        .eq(InterviewSessionEventEntity::getSessionId, sessionId));
    agentRunStepMapper.delete(Wrappers.<AgentRunStepEntity>lambdaQuery()
        .eq(AgentRunStepEntity::getUserId, userId)
        .eq(AgentRunStepEntity::getSessionId, sessionId));
    if (agentRunMapper != null) {
      agentRunMapper.delete(Wrappers.<AgentRunEntity>lambdaQuery()
          .eq(AgentRunEntity::getUserId, userId)
          .eq(AgentRunEntity::getSessionId, sessionId));
    }
  }

  private void deletePreparationArtifacts(
      Long userId,
      String sessionId,
      List<PreparationRunEntity> preparations
  ) {
    List<String> runIds = preparations.stream()
        .map(PreparationRunEntity::getRunId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    if (!runIds.isEmpty()) {
      List<String> snapshotIds = evidenceSnapshotMapper.selectList(
              Wrappers.<EvidenceSnapshotEntity>lambdaQuery()
                  .select(EvidenceSnapshotEntity::getSnapshotId)
                  .eq(EvidenceSnapshotEntity::getUserId, userId)
                  .eq(EvidenceSnapshotEntity::getContextType, PREPARATION_EVIDENCE_CONTEXT)
                  .in(EvidenceSnapshotEntity::getContextId, runIds))
          .stream()
          .map(EvidenceSnapshotEntity::getSnapshotId)
          .filter(Objects::nonNull)
          .distinct()
          .toList();
      if (!snapshotIds.isEmpty()) {
        evidenceSnapshotRefMapper.delete(Wrappers.<EvidenceSnapshotRefEntity>lambdaQuery()
            .eq(EvidenceSnapshotRefEntity::getUserId, userId)
            .in(EvidenceSnapshotRefEntity::getSnapshotId, snapshotIds));
        evidenceSnapshotMapper.delete(Wrappers.<EvidenceSnapshotEntity>lambdaQuery()
            .eq(EvidenceSnapshotEntity::getUserId, userId)
            .in(EvidenceSnapshotEntity::getSnapshotId, snapshotIds));
      }
    }
    preparationRunMapper.delete(Wrappers.<PreparationRunEntity>lambdaQuery()
        .eq(PreparationRunEntity::getUserId, userId)
        .eq(PreparationRunEntity::getSessionId, sessionId));
  }

  private void validate(Long userId, Long sessionPkId, String sessionId) {
    if (userId == null || userId <= 0 || sessionPkId == null || sessionPkId <= 0
        || sessionId == null || sessionId.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "删除面试会话参数不完整");
    }
  }
}
