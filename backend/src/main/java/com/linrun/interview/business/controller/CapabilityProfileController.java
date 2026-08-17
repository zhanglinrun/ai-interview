package com.linrun.interview.business.controller;

import com.linrun.interview.common.result.Result;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.vo.CapabilityProfileView;
import com.linrun.interview.business.service.CapabilityProfileService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/capability-profile")
@RequiredArgsConstructor
public class CapabilityProfileController {

  private final CapabilityProfileService profileService;

  @GetMapping
  public Result<List<CapabilityProfileView>> list() {
    return Result.success(profileService.list(UserContext.requireUserId()));
  }
}
