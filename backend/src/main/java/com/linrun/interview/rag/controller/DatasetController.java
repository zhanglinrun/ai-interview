package com.linrun.interview.rag.controller;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.rag.model.DatasetGenerateRequest;
import com.linrun.interview.rag.model.DatasetGenerateResult;
import com.linrun.interview.rag.service.RagDatasetService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 评测 Dataset 接口：复用生产同步查询，只返回最终答案与同一次检索块。
 *
 * <p>走 {@code queryForEvaluation}，不是 SSE，因此没有 progress / card 事件。
 * 旧 {@code /knowledge-bases/dataset/generate} 与 {@code export-qa} 不作正式评测入口。
 */
@Validated
@RestController
@RequiredArgsConstructor
@Tag(name = "RAG Dataset", description = "评测用单题生成：question / answer / references")
@RequestMapping("/api/v1/dataset")
public class DatasetController {

  private final RagDatasetService ragDatasetService;

  @GetMapping("/generate")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 8)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 8)
  public Result<DatasetGenerateResult> generateGet(
      @RequestParam("question") @NotBlank String question,
      @RequestParam("knowledgeBaseIds") @NotEmpty List<Long> knowledgeBaseIds) {
    return Result.success(ragDatasetService.generateForRagas(knowledgeBaseIds, question));
  }

  @PostMapping("/generate")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 8)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 8)
  public Result<DatasetGenerateResult> generatePost(
      @Valid @RequestBody DatasetGenerateRequest request) {
    return Result.success(
        ragDatasetService.generateForRagas(request.knowledgeBaseIds(), request.question()));
  }
}
