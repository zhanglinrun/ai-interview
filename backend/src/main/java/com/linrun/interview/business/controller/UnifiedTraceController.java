package com.linrun.interview.business.controller;

import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.service.ToolRegistry;
import com.linrun.interview.business.service.UnifiedTraceService;
import com.linrun.interview.business.vo.UnifiedTraceDTO;
import com.linrun.interview.common.result.Result;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Unified, user-scoped trace/timeline endpoints. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class UnifiedTraceController {
  private final UnifiedTraceService traceService;
  private final ToolRegistry toolRegistry;

  @GetMapping("/traces/{traceId}")
  public Result<UnifiedTraceDTO> trace(@PathVariable String traceId,
                                      @RequestParam(defaultValue = "100") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
    return Result.success(traceService.get(traceId, UserContext.requireUserId(), limit, offset));
  }

  @GetMapping("/interviews/sessions/{sessionId}/timeline")
  public Result<UnifiedTraceDTO> timeline(@PathVariable String sessionId,
                                          @RequestParam(defaultValue = "200") int limit,
                                          @RequestParam(defaultValue = "0") int offset) {
    return Result.success(traceService.timeline(sessionId, UserContext.requireUserId(), limit, offset));
  }

  @GetMapping("/agent/tools")
  public Result<List<ToolDescriptorView>> tools() {
    UserContext.requireAdmin();
    return Result.success(toolRegistry.descriptors().stream()
        .map(descriptor -> new ToolDescriptorView(descriptor.name(), descriptor.allowedRoles(),
            descriptor.cacheable(), descriptor.idempotent(), descriptor.timeoutMs(), descriptor.version()))
        .toList());
  }

  @GetMapping("/agent/tools/stats")
  public Result<UnifiedTraceDTO.ToolStatsView> toolStats() {
    UserContext.requireAdmin();
    return Result.success(traceService.toolStats());
  }

  public record ToolDescriptorView(String name, java.util.Set<String> allowedRoles,
                                   boolean cacheable, boolean idempotent,
                                   long timeoutMs, String version) {
  }
}
