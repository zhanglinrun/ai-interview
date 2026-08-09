package com.linrun.interview.business.service;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.business.client.JudgeClient;
import com.linrun.interview.business.vo.CodingAttemptDTO;
import com.linrun.interview.business.vo.CodingProblemDetailDTO;
import com.linrun.interview.business.vo.CodingProblemSummaryDTO;
import com.linrun.interview.business.vo.CreateCodingAttemptRequest;
import com.linrun.interview.business.vo.JudgeSubmissionDTO;
import com.linrun.interview.business.vo.SubmitCodeRequest;
import com.linrun.interview.business.constant.CodingAttemptMode;
import com.linrun.interview.business.constant.CodingLanguage;
import com.linrun.interview.business.service.AlgorithmCatalogService;
import com.linrun.interview.business.service.CodingAttemptService;
import com.linrun.interview.business.service.CodingJudgeService;
import com.linrun.interview.business.constant.JobCodingLanguage;
import com.linrun.interview.business.entity.JobInterviewQuestionEntity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 将版本化 Hot 100 题库与 Judge0 客观判题接入岗位实战。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlgorithmJobInterviewAdapter
    implements AlgorithmPreparationPort, JobInterviewCodingPort {

  private final AlgorithmCatalogService catalogService;
  private final CodingAttemptService attemptService;
  private final CodingJudgeService judgeService;
  private final JudgeClient judgeClient;

  @Override
  public Reservation reserve(Long userId, Long jobDescriptionId, JobCodingLanguage language) {
    CodingLanguage codingLanguage = toAlgorithmLanguage(language);
    List<CodingProblemSummaryDTO> candidates = catalogService.listEnabled(codingLanguage, null);
    if (candidates.isEmpty()) {
      return Reservation.unavailable("HOT100_CONTENT_UNAVAILABLE");
    }
    int seed = Long.hashCode(userId == null ? 0L : userId)
        ^ Long.hashCode(jobDescriptionId == null ? 0L : jobDescriptionId);
    CodingProblemSummaryDTO selected = candidates.get(Math.floorMod(seed, candidates.size()));
    CodingProblemDetailDTO detail = catalogService.getDetail(selected.problemVersionId());
    String question = detail.statement()
        + "\n\n请先澄清输入约束，说明思路与复杂度，再使用当前锁定语言完成函数实现并自测。";
    boolean judgeAvailable = judgeClient.available(codingLanguage);
    return new Reservation(
        true,
        judgeAvailable,
        selected.platformProblemId(),
        String.valueOf(selected.problemVersionId()),
        question,
        judgeAvailable ? null : "JUDGE0_NOT_CONFIGURED");
  }

  @Override
  public CodeTemplate starter(
      JobInterviewQuestionEntity question,
      JobCodingLanguage language
  ) {
    try {
      Long problemVersionId = Long.valueOf(question.getQuestionTemplateVersion());
      CodingLanguage target = toAlgorithmLanguage(language);
      return catalogService.getDetail(problemVersionId).languages().stream()
          .filter(item -> item.language() == target)
          .findFirst()
          .map(item -> new CodeTemplate(item.template(), item.functionSignature()))
          .orElseGet(() -> new CodeTemplate("", ""));
    } catch (Exception e) {
      log.warn("岗位实战算法模板不可用: questionId={}", question.getId(), e);
      return new CodeTemplate("", "");
    }
  }

  @Override
  public CodingOutcome submit(
      Long userId,
      String sessionId,
      JobInterviewQuestionEntity question,
      JobCodingLanguage language,
      String commandId,
      String sourceCode
  ) {
    try {
      Long problemVersionId = Long.valueOf(question.getQuestionTemplateVersion());
      CodingAttemptDTO attempt = attemptService.create(userId, new CreateCodingAttemptRequest(
          problemVersionId,
          toAlgorithmLanguage(language),
          CodingAttemptMode.JOB_INTERVIEW,
          contextId(sessionId, question.getId())));
      JudgeSubmissionDTO submission = judgeService.submitHidden(
          userId,
          attempt.attemptId(),
          new SubmitCodeRequest(commandId, sourceCode));
      return new CodingOutcome(
          submission.submissionId(), submission.status().name(), submission.passedCount(),
          submission.totalCount(), submission.diagnostic(), submission.timeMs(),
          submission.memoryKb(), submission.failureCode(), submission.pendingRejudge());
    } catch (BusinessException | NumberFormatException e) {
      log.warn(
          "岗位实战算法判题降级: sessionId={}, questionId={}, code={}",
          sessionId, question.getId(), e instanceof BusinessException business
              ? business.getCode() : "INVALID_PROBLEM_VERSION");
      return CodingOutcome.unavailable(
          "ALGORITHM_SUBMISSION_UNAVAILABLE", "判题暂不可用，源码已保留，可稍后补判");
    } catch (Exception e) {
      log.warn("岗位实战算法判题异常，保存为待补判: sessionId={}, questionId={}",
          sessionId, question.getId(), e);
      return CodingOutcome.unavailable(
          "JUDGE_UNAVAILABLE", "判题服务暂不可用，源码已保留，可稍后补判");
    }
  }

  private CodingLanguage toAlgorithmLanguage(JobCodingLanguage language) {
    return language == JobCodingLanguage.PYTHON3
        ? CodingLanguage.PYTHON3 : CodingLanguage.JAVA21;
  }

  private String contextId(String sessionId, Long questionId) {
    String value = sessionId + ":" + questionId;
    return value.length() <= 80 ? value : value.substring(0, 80);
  }
}
