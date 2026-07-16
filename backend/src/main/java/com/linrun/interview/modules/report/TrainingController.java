package com.linrun.interview.modules.report;

import com.linrun.interview.common.result.Result;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.report.dto.TrainingContracts.CompleteTrainingRequest;
import com.linrun.interview.modules.report.dto.TrainingContracts.CreateTrainingRequest;
import com.linrun.interview.modules.report.dto.TrainingContracts.TrainingInteractionRequest;
import com.linrun.interview.modules.report.dto.TrainingContracts.TrainingTaskView;
import com.linrun.interview.modules.report.model.TrainingStatus;
import com.linrun.interview.modules.report.service.TrainingService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/training/tasks")
@RequiredArgsConstructor
public class TrainingController {

  private final TrainingService trainingService;

  @GetMapping
  public Result<List<TrainingTaskView>> list(
      @RequestParam(required = false) TrainingStatus status
  ) {
    return Result.success(trainingService.list(UserContext.requireUserId(), status));
  }

  @PostMapping
  public Result<TrainingTaskView> create(
      @Valid @RequestBody CreateTrainingRequest request
  ) {
    return Result.success(trainingService.createManual(UserContext.requireUserId(), request));
  }

  @PostMapping("/{taskId}/interactions")
  public Result<TrainingTaskView> recordInteraction(
      @PathVariable String taskId,
      @RequestBody TrainingInteractionRequest request
  ) {
    return Result.success(trainingService.recordInteraction(
        UserContext.requireUserId(), taskId, request));
  }

  @PostMapping("/{taskId}/complete")
  public Result<TrainingTaskView> complete(
      @PathVariable String taskId,
      @Valid @RequestBody CompleteTrainingRequest request
  ) {
    return Result.success(trainingService.complete(
        UserContext.requireUserId(), taskId, request));
  }
}
