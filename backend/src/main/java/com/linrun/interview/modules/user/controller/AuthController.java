package com.linrun.interview.modules.user.controller;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.modules.user.model.AuthResponse;
import com.linrun.interview.modules.user.model.LoginRequest;
import com.linrun.interview.modules.user.model.RegisterRequest;
import com.linrun.interview.modules.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器：注册、登录、刷新 token。
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, interval = 1,
        timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 60, interval = 1,
        timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 1,
        timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @Operation(summary = "刷新 access token")
    @PostMapping("/refresh")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 30, interval = 1,
        timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<AuthResponse> refresh(@RequestHeader("Refresh-Token") String refreshToken) {
        return Result.success(authService.refreshToken(refreshToken));
    }
}
