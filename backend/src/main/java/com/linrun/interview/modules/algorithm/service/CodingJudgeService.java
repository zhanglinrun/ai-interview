package com.linrun.interview.modules.algorithm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.infrastructure.file.FileHashService;
import com.linrun.interview.modules.algorithm.client.JudgeClient;
import com.linrun.interview.modules.algorithm.client.JudgeClientResult;
import com.linrun.interview.modules.algorithm.client.JudgeRequest;
import com.linrun.interview.modules.algorithm.dto.JudgeSubmissionDTO;
import com.linrun.interview.modules.algorithm.dto.SubmitCodeRequest;
import com.linrun.interview.modules.algorithm.mapper.CodingAttemptMapper;
import com.linrun.interview.modules.algorithm.mapper.JudgeSubmissionMapper;
import com.linrun.interview.modules.algorithm.model.CodingAttemptEntity;
import com.linrun.interview.modules.algorithm.model.CodingAttemptStatus;
import com.linrun.interview.modules.algorithm.model.CodingProblemVersionEntity;
import com.linrun.interview.modules.algorithm.model.JudgeSubmissionEntity;
import com.linrun.interview.modules.algorithm.model.TestSuiteType;
import com.linrun.interview.modules.algorithm.service.CodingSubmissionPersistenceService.SubmissionReservation;
import com.linrun.interview.modules.algorithm.service.TestHarnessFactory.TestHarness;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 非事务编排：落幂等记录 -> 事务外 Judge0 -> 短事务保存客观事实。 */
@Service
@RequiredArgsConstructor
public class CodingJudgeService {

  private final CodingAttemptService attemptService;
  private final AlgorithmCatalogService catalogService;
  private final TestHarnessFactory harnessFactory;
  private final CodingSubmissionPersistenceService persistenceService;
  private final CodingAttemptMapper attemptMapper;
  private final JudgeSubmissionMapper submissionMapper;
  private final JudgeClient judgeClient;
  private final FileHashService fileHashService;

  public JudgeSubmissionDTO runPublic(
      Long userId,
      String attemptId,
      SubmitCodeRequest request
  ) {
    return execute(userId, attemptId, request, TestSuiteType.PUBLIC);
  }

  public JudgeSubmissionDTO submitHidden(
      Long userId,
      String attemptId,
      SubmitCodeRequest request
  ) {
    return execute(userId, attemptId, request, TestSuiteType.HIDDEN);
  }

  public JudgeSubmissionDTO get(Long userId, String submissionId) {
    JudgeSubmissionEntity entity = persistenceService.requireOwned(userId, submissionId);
    CodingAttemptEntity attempt = requireAttempt(entity);
    return toDTO(entity, attempt);
  }

  public List<JudgeSubmissionDTO> listForAttempt(Long userId, String attemptId) {
    CodingAttemptEntity attempt = attemptService.requireOwned(userId, attemptId);
    return submissionMapper.selectList(Wrappers.<JudgeSubmissionEntity>lambdaQuery()
            .eq(JudgeSubmissionEntity::getUserId, userId)
            .eq(JudgeSubmissionEntity::getAttemptId, attempt.getId())
            .orderByDesc(JudgeSubmissionEntity::getCreatedAt))
        .stream().map(entity -> toDTO(entity, attempt)).toList();
  }

  public JudgeSubmissionDTO rejudge(Long userId, String submissionId) {
    JudgeSubmissionEntity submission = persistenceService.reserveRejudge(userId, submissionId);
    CodingAttemptEntity attempt = requireAttempt(submission);
    CodingProblemVersionEntity version = catalogService.requireEnabledVersion(
        attempt.getProblemVersionId(), attempt.getLanguage());
    TestHarness harness = harnessFactory.build(
        version, attempt.getLanguage(), submission.getSuiteType(), submission.getSourceCode());
    return toDTO(runExternal(submission, attempt, harness), attempt);
  }

  private JudgeSubmissionDTO execute(
      Long userId,
      String attemptId,
      SubmitCodeRequest request,
      TestSuiteType suiteType
  ) {
    CodingAttemptEntity attempt = attemptService.requireOwned(userId, attemptId);
    if (attempt.getStatus() == CodingAttemptStatus.ABORTED
        || attempt.getStatus() == CodingAttemptStatus.COMPLETED) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "当前算法作答已结束");
    }
    CodingProblemVersionEntity version = catalogService.requireEnabledVersion(
        attempt.getProblemVersionId(), attempt.getLanguage());
    TestHarness harness = harnessFactory.build(
        version, attempt.getLanguage(), suiteType, request.sourceCode());
    SubmissionReservation reservation = persistenceService.reserve(
        attempt, request.idempotencyKey().trim(), suiteType, request.sourceCode(),
        hash(request.sourceCode()), harness.totalCount(), judgeClient.providerName());
    if (!reservation.fresh()) {
      return toDTO(reservation.submission(), attempt);
    }
    return toDTO(runExternal(reservation.submission(), attempt, harness), attempt);
  }

  private JudgeSubmissionEntity runExternal(
      JudgeSubmissionEntity submission,
      CodingAttemptEntity attempt,
      TestHarness harness
  ) {
    JudgeClientResult result;
    try {
      result = judgeClient.judge(new JudgeRequest(
          submission.getSubmissionId(), attempt.getLanguage(), harness.sourceCode(),
          harness.expectedOutput(), harness.totalCount()));
    } catch (Exception ignored) {
      result = JudgeClientResult.unavailable(
          harness.totalCount(), "JUDGE_UNAVAILABLE", "判题服务暂时不可用，可稍后补判");
    }
    return persistenceService.complete(submission.getId(), result);
  }

  private CodingAttemptEntity requireAttempt(JudgeSubmissionEntity submission) {
    CodingAttemptEntity attempt = attemptMapper.selectOne(
        Wrappers.<CodingAttemptEntity>lambdaQuery()
            .eq(CodingAttemptEntity::getId, submission.getAttemptId())
            .eq(CodingAttemptEntity::getUserId, submission.getUserId()));
    if (attempt == null) {
      throw new BusinessException(ErrorCode.CODING_ATTEMPT_NOT_FOUND);
    }
    return attempt;
  }

  private JudgeSubmissionDTO toDTO(
      JudgeSubmissionEntity entity,
      CodingAttemptEntity attempt
  ) {
    return new JudgeSubmissionDTO(
        entity.getSubmissionId(), attempt.getAttemptId(), entity.getSuiteType(),
        entity.getLanguage(), entity.getStatus(), entity.getPassedCount(),
        entity.getTotalCount(), entity.getDiagnostic(), entity.getTimeMs(),
        entity.getMemoryKb(), entity.getFailureCode(), entity.getStatus().pendingRejudge(),
        entity.getSubmittedAt(), entity.getCompletedAt());
  }

  private String hash(String sourceCode) {
    return fileHashService.calculateHash(sourceCode.getBytes(StandardCharsets.UTF_8));
  }
}
