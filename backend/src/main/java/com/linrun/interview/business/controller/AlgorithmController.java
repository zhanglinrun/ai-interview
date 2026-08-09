package com.linrun.interview.business.controller;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.vo.CodingAttemptDTO;
import com.linrun.interview.business.vo.CodingDraftDTO;
import com.linrun.interview.business.vo.CodingProblemDetailDTO;
import com.linrun.interview.business.vo.CodingProblemSummaryDTO;
import com.linrun.interview.business.vo.CreateCodingAttemptRequest;
import com.linrun.interview.business.vo.JudgeSubmissionDTO;
import com.linrun.interview.business.vo.SaveCodingDraftRequest;
import com.linrun.interview.business.vo.SubmitCodeRequest;
import com.linrun.interview.business.constant.CodingLanguage;
import com.linrun.interview.business.service.AlgorithmCatalogService;
import com.linrun.interview.business.service.CodingAttemptService;
import com.linrun.interview.business.service.CodingJudgeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/algorithms")
@RequiredArgsConstructor
@Tag(name = "算法面试", description = "Hot 100 题库、草稿、外部客观判题与补判")
public class AlgorithmController {

  private final AlgorithmCatalogService catalogService;
  private final CodingAttemptService attemptService;
  private final CodingJudgeService judgeService;

  @GetMapping("/problems")
  public Result<List<CodingProblemSummaryDTO>> listProblems(
      @RequestParam(required = false) CodingLanguage language,
      @RequestParam(required = false) String tag
  ) {
    return Result.success(catalogService.listEnabled(language, tag));
  }

  @GetMapping("/problem-versions/{problemVersionId}")
  public Result<CodingProblemDetailDTO> getProblem(@PathVariable Long problemVersionId) {
    return Result.success(catalogService.getDetail(problemVersionId));
  }

  @PostMapping("/attempts")
  public Result<CodingAttemptDTO> createAttempt(
      @Valid @RequestBody CreateCodingAttemptRequest request
  ) {
    return Result.success(attemptService.create(UserContext.requireUserId(), request));
  }

  @GetMapping("/attempts/{attemptId}")
  public Result<CodingAttemptDTO> getAttempt(@PathVariable String attemptId) {
    return Result.success(attemptService.get(UserContext.requireUserId(), attemptId));
  }

  @GetMapping("/attempts/{attemptId}/draft")
  public Result<CodingDraftDTO> getDraft(@PathVariable String attemptId) {
    return Result.success(attemptService.getDraft(UserContext.requireUserId(), attemptId));
  }

  @PutMapping("/attempts/{attemptId}/draft")
  public Result<CodingDraftDTO> saveDraft(
      @PathVariable String attemptId,
      @Valid @RequestBody SaveCodingDraftRequest request
  ) {
    return Result.success(attemptService.saveDraft(
        UserContext.requireUserId(), attemptId, request));
  }

  @PostMapping("/attempts/{attemptId}/run")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<JudgeSubmissionDTO> runPublic(
      @PathVariable String attemptId,
      @Valid @RequestBody SubmitCodeRequest request
  ) {
    return Result.success(judgeService.runPublic(
        UserContext.requireUserId(), attemptId, request));
  }

  @PostMapping("/attempts/{attemptId}/submissions")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 5)
  public Result<JudgeSubmissionDTO> submitHidden(
      @PathVariable String attemptId,
      @Valid @RequestBody SubmitCodeRequest request
  ) {
    return Result.success(judgeService.submitHidden(
        UserContext.requireUserId(), attemptId, request));
  }

  @GetMapping("/attempts/{attemptId}/submissions")
  public Result<List<JudgeSubmissionDTO>> listSubmissions(@PathVariable String attemptId) {
    return Result.success(judgeService.listForAttempt(
        UserContext.requireUserId(), attemptId));
  }

  @GetMapping("/submissions/{submissionId}")
  public Result<JudgeSubmissionDTO> getSubmission(@PathVariable String submissionId) {
    return Result.success(judgeService.get(UserContext.requireUserId(), submissionId));
  }

  @PostMapping("/submissions/{submissionId}/rejudge")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 3)
  public Result<JudgeSubmissionDTO> rejudge(@PathVariable String submissionId) {
    return Result.success(judgeService.rejudge(UserContext.requireUserId(), submissionId));
  }
}
