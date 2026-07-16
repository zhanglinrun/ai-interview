package com.linrun.interview.modules.report;

import com.linrun.interview.common.result.Result;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.report.dto.ReportContracts.ReportView;
import com.linrun.interview.modules.report.service.ReportApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports/sessions/{sessionId}")
@RequiredArgsConstructor
public class ReportController {

  private final ReportApplicationService reportService;

  @GetMapping
  public Result<ReportView> get(@PathVariable String sessionId) {
    return Result.success(reportService.getExisting(UserContext.requireUserId(), sessionId));
  }

  @PostMapping("/generate")
  public Result<ReportView> generate(@PathVariable String sessionId) {
    return Result.success(reportService.getOrCreate(UserContext.requireUserId(), sessionId));
  }

  @PostMapping("/retry")
  public Result<ReportView> retry(@PathVariable String sessionId) {
    return Result.success(reportService.retry(UserContext.requireUserId(), sessionId));
  }
}
