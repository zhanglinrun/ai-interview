package com.linrun.interview.ai.controller;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.ai.dto.MyProviderDTO;
import com.linrun.interview.ai.dto.ProviderTestResult;
import com.linrun.interview.ai.dto.SaveMyProviderRequest;
import com.linrun.interview.ai.service.UserLlmProviderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户级 LLM Provider（BYOK）接口：读/存/删/测试自己的「我的模型」。
 * 按当前登录用户解析（{@link UserContext#requireUserId()}），非管理员亦可访问。
 */
@RestController
@RequestMapping("/api/v1/llm-provider/mine")
@RequiredArgsConstructor
@Slf4j
public class UserLlmProviderController {

  private final UserLlmProviderService userLlmProviderService;

  @GetMapping
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 30)
  public Result<MyProviderDTO> getMine() {
    return Result.success(userLlmProviderService.getMine(UserContext.requireUserId()));
  }

  @PutMapping
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<Void> saveMine(@RequestBody @Valid SaveMyProviderRequest request) {
    userLlmProviderService.saveMine(UserContext.requireUserId(), request);
    return Result.success();
  }

  @DeleteMapping
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<Void> deleteMine() {
    userLlmProviderService.deleteMine(UserContext.requireUserId());
    return Result.success();
  }

  @PostMapping("/test")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<ProviderTestResult> testMine() {
    return Result.success(userLlmProviderService.testMine(UserContext.requireUserId()));
  }
}
