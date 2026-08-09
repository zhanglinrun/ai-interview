package com.linrun.interview.rag.controller;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.rag.model.EvalRunRequest;
import com.linrun.interview.rag.model.EvalRunResponse;
import com.linrun.interview.rag.model.EvalRunSummary;
import com.linrun.interview.rag.service.EvalRunService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一评测闭环控制器。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "统一评测", description = "意图识别、RAG 检索与基线回归评测")
@RequestMapping("/api/v1/rag/evaluations")
public class EvalRunController {

  private final EvalRunService evalRunService;

  /**
   * 运行统一评测并与最近基线做回归对比。
   */
  @PostMapping("/run")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
  public Result<EvalRunResponse> runEvaluation(@Valid @RequestBody EvalRunRequest request) {
    return Result.success(evalRunService.run(request));
  }

  @GetMapping
  public Result<java.util.List<EvalRunSummary>> list(
      @RequestParam(defaultValue = "20") int limit) {
    return Result.success(evalRunService.listRecent(limit));
  }

  @GetMapping("/{runId}")
  public Result<EvalRunResponse> get(@PathVariable String runId) {
    return Result.success(evalRunService.get(runId));
  }
}
