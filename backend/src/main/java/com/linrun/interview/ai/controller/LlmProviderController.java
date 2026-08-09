package com.linrun.interview.ai.controller;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.ai.dto.CreateProviderRequest;
import com.linrun.interview.ai.dto.DefaultProviderDTO;
import com.linrun.interview.ai.dto.ProviderDTO;
import com.linrun.interview.ai.dto.ProviderTestResult;
import com.linrun.interview.ai.dto.UpdateProviderRequest;
import com.linrun.interview.ai.service.LlmProviderConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/llm-provider")
@RequiredArgsConstructor
@Slf4j
public class LlmProviderController {

  private final LlmProviderConfigService configService;

  @GetMapping("/list")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<List<ProviderDTO>> listProviders() {
    UserContext.requireAdmin();
    return Result.success(configService.listProviders());
  }

  @GetMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<ProviderDTO> getProvider(@PathVariable String id) {
    UserContext.requireAdmin();
    return Result.success(configService.getProvider(id));
  }

  @PostMapping
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> createProvider(@RequestBody @Valid CreateProviderRequest request) {
    UserContext.requireAdmin();
    configService.createProvider(request);
    return Result.success();
  }

  @PutMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateProvider(@PathVariable String id,
      @RequestBody UpdateProviderRequest request) {
    UserContext.requireAdmin();
    configService.updateProvider(id, request);
    return Result.success();
  }

  @DeleteMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> deleteProvider(@PathVariable String id) {
    UserContext.requireAdmin();
    configService.deleteProvider(id);
    return Result.success();
  }

  @PostMapping("/{id}/test")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<ProviderTestResult> testProvider(@PathVariable String id) {
    UserContext.requireAdmin();
    return Result.success(configService.testProvider(id));
  }

  @PostMapping("/reload")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> reloadProviders() {
    UserContext.requireAdmin();
    configService.reloadProviders();
    return Result.success();
  }

  @GetMapping("/default-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<DefaultProviderDTO> getDefaultProvider() {
    UserContext.requireAdmin();
    return Result.success(configService.getDefaultProvider());
  }

  @PutMapping("/default-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateDefaultProvider(@RequestBody DefaultProviderDTO request) {
    UserContext.requireAdmin();
    configService.updateDefaultProvider(request);
    return Result.success();
  }

  @PutMapping("/default-embedding-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateDefaultEmbeddingProvider(@RequestBody DefaultProviderDTO request) {
    UserContext.requireAdmin();
    configService.updateDefaultEmbeddingProvider(request);
    return Result.success();
  }

}
