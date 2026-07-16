package com.linrun.interview.modules.jobinterview;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.AbortCommand;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.ClarificationCommand;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.CodeCommand;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.CodeDraftView;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.CommandEnvelope;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.CommandResult;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.CreatePreparationRequest;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.EventView;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.PreparationView;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.SessionView;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.SubmitAnswerCommand;
import com.linrun.interview.modules.jobinterview.service.JobInterviewEventStreamService;
import com.linrun.interview.modules.jobinterview.service.JobInterviewPreparationService;
import com.linrun.interview.modules.jobinterview.service.JobInterviewRuntimeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** JD 岗位实战准备、版本化指令和可恢复 SSE API。 */
@RestController
@RequestMapping("/api/job-interviews")
@RequiredArgsConstructor
@Tag(name = "岗位实战", description = "围绕冻结 JD 的四阶段真实考核闭环")
public class JobInterviewController {

  private final JobInterviewPreparationService preparationService;
  private final JobInterviewRuntimeService runtimeService;
  private final JobInterviewEventStreamService eventStreamService;

  @PostMapping("/preparations")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 5)
  public Result<PreparationView> prepare(
      @Valid @RequestBody CreatePreparationRequest request
  ) {
    return Result.success(preparationService.create(UserContext.requireUserId(), request));
  }

  @GetMapping("/preparations/{runId}")
  public Result<PreparationView> preparation(@PathVariable String runId) {
    return Result.success(preparationService.get(UserContext.requireUserId(), runId));
  }

  @GetMapping("/sessions/{sessionId}")
  public Result<SessionView> session(@PathVariable String sessionId) {
    return Result.success(runtimeService.get(UserContext.requireUserId(), sessionId));
  }

  @PostMapping("/sessions/{sessionId}/start")
  public Result<CommandResult> start(
      @PathVariable String sessionId,
      @Valid @RequestBody CommandEnvelope command
  ) {
    return Result.success(runtimeService.start(
        UserContext.requireUserId(), sessionId, command));
  }

  @PostMapping("/sessions/{sessionId}/answers")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<CommandResult> submitAnswer(
      @PathVariable String sessionId,
      @Valid @RequestBody SubmitAnswerCommand command
  ) {
    return Result.success(runtimeService.submitAnswer(
        UserContext.requireUserId(), sessionId, command));
  }

  @PostMapping("/sessions/{sessionId}/clarification")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<CommandResult> clarification(
      @PathVariable String sessionId,
      @Valid @RequestBody ClarificationCommand command
  ) {
    return Result.success(runtimeService.clarification(
        UserContext.requireUserId(), sessionId, command));
  }

  @PutMapping("/sessions/{sessionId}/code")
  public Result<CommandResult> saveCode(
      @PathVariable String sessionId,
      @Valid @RequestBody CodeCommand command
  ) {
    return Result.success(runtimeService.saveCode(
        UserContext.requireUserId(), sessionId, command));
  }

  @GetMapping("/sessions/{sessionId}/code")
  public Result<CodeDraftView> codeDraft(
      @PathVariable String sessionId,
      @RequestParam Long questionId
  ) {
    return Result.success(runtimeService.getCodeDraft(
        UserContext.requireUserId(), sessionId, questionId));
  }

  @PostMapping("/sessions/{sessionId}/code/submit")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 5)
  public Result<CommandResult> submitCode(
      @PathVariable String sessionId,
      @Valid @RequestBody CodeCommand command
  ) {
    return Result.success(runtimeService.submitCode(
        UserContext.requireUserId(), sessionId, command));
  }

  @PostMapping("/sessions/{sessionId}/continue")
  public Result<CommandResult> continueInterview(
      @PathVariable String sessionId,
      @Valid @RequestBody CommandEnvelope command
  ) {
    return Result.success(runtimeService.continueInterview(
        UserContext.requireUserId(), sessionId, command));
  }

  @PostMapping("/sessions/{sessionId}/finish")
  public Result<CommandResult> finish(
      @PathVariable String sessionId,
      @Valid @RequestBody CommandEnvelope command
  ) {
    return Result.success(runtimeService.finish(
        UserContext.requireUserId(), sessionId, command));
  }

  @PostMapping("/sessions/{sessionId}/abort")
  public Result<CommandResult> abort(
      @PathVariable String sessionId,
      @Valid @RequestBody AbortCommand command
  ) {
    return Result.success(runtimeService.abort(
        UserContext.requireUserId(), sessionId, command));
  }

  @GetMapping(value = "/sessions/{sessionId}/events",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<EventView>> events(
      @PathVariable String sessionId,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
      @RequestParam(required = false, defaultValue = "0") Long afterEventId
  ) {
    Long userId = UserContext.requireUserId();
    long cursor = Math.max(afterEventId == null ? 0L : afterEventId,
        parseLastEventId(lastEventId));
    return eventStreamService.stream(userId, sessionId, cursor);
  }

  private long parseLastEventId(String value) {
    if (value == null || value.isBlank()) {
      return 0L;
    }
    try {
      return Math.max(0L, Long.parseLong(value.trim()));
    } catch (NumberFormatException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "Last-Event-ID 必须是非负整数");
    }
  }
}
