package com.linrun.interview.modules.report;

import com.linrun.interview.common.result.Result;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.report.dto.LlmUsageDTO;
import com.linrun.interview.modules.report.service.LlmUsageService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/llm-usage")
@RequiredArgsConstructor
public class LlmUsageController {

  private final LlmUsageService usageService;

  @GetMapping
  public Result<List<LlmUsageDTO>> list(
      @RequestParam(required = false) String sessionId,
      @RequestParam(required = false) String reportId,
      @RequestParam(defaultValue = "50") int limit
  ) {
    return Result.success(usageService.list(
        UserContext.requireUserId(), sessionId, reportId, limit));
  }
}
