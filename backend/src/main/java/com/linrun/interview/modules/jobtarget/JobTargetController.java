package com.linrun.interview.modules.jobtarget;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.jobtarget.dto.ConfirmJobCapabilitiesRequest;
import com.linrun.interview.modules.jobtarget.dto.CreateJobDescriptionRequest;
import com.linrun.interview.modules.jobtarget.dto.CreateJobDescriptionVersionRequest;
import com.linrun.interview.modules.jobtarget.dto.JdAnalysisResultDTO;
import com.linrun.interview.modules.jobtarget.dto.JobCapabilityMappingDTO;
import com.linrun.interview.modules.jobtarget.dto.JobDescriptionDTO;
import com.linrun.interview.modules.jobtarget.service.JdAnalysisService;
import com.linrun.interview.modules.jobtarget.service.JobCapabilityMappingService;
import com.linrun.interview.modules.jobtarget.service.JobDescriptionService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 岗位目标、JD 版本、能力确认与冻结快照 API。 */
@RestController
@RequestMapping("/api/job-targets")
@RequiredArgsConstructor
public class JobTargetController {

  private final JobDescriptionService jobDescriptionService;
  private final JdAnalysisService jdAnalysisService;
  private final JobCapabilityMappingService mappingService;

  @PostMapping
  public Result<JobDescriptionDTO> create(
      @Valid @RequestBody CreateJobDescriptionRequest request
  ) {
    return Result.success(jobDescriptionService.create(UserContext.requireUserId(), request));
  }

  @GetMapping
  public Result<List<JobDescriptionDTO>> list() {
    return Result.success(jobDescriptionService.list(UserContext.requireUserId()));
  }

  @GetMapping("/{id}")
  public Result<JobDescriptionDTO> get(@PathVariable Long id) {
    return Result.success(jobDescriptionService.get(UserContext.requireUserId(), id));
  }

  @PostMapping("/{id}/versions")
  public Result<JobDescriptionDTO> createVersion(
      @PathVariable Long id,
      @Valid @RequestBody CreateJobDescriptionVersionRequest request
  ) {
    return Result.success(jobDescriptionService.createVersion(
        UserContext.requireUserId(), id, request));
  }

  @PostMapping("/{id}/analyze")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 5)
  public Result<JdAnalysisResultDTO> analyze(@PathVariable Long id) {
    return Result.success(jdAnalysisService.analyze(UserContext.requireUserId(), id));
  }

  @PutMapping("/{id}/capabilities")
  public Result<List<JobCapabilityMappingDTO>> confirmCapabilities(
      @PathVariable Long id,
      @Valid @RequestBody ConfirmJobCapabilitiesRequest request
  ) {
    return Result.success(mappingService.confirm(UserContext.requireUserId(), id, request));
  }

  @PostMapping("/{id}/freeze")
  public Result<JobDescriptionDTO> freeze(@PathVariable Long id) {
    return Result.success(jobDescriptionService.freeze(UserContext.requireUserId(), id));
  }

  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    jobDescriptionService.delete(UserContext.requireUserId(), id);
    return Result.success();
  }
}
