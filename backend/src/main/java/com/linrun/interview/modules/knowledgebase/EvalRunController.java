package com.linrun.interview.modules.knowledgebase;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.modules.knowledgebase.model.EvalRunRequest;
import com.linrun.interview.modules.knowledgebase.model.EvalRunResponse;
import com.linrun.interview.modules.knowledgebase.service.EvalRunService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一评测闭环控制器。
 */
@RestController
@RequestMapping("/api/eval")
@RequiredArgsConstructor
@Tag(name = "统一评测", description = "意图识别、RAG 检索与基线回归评测")
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
}
