package com.linrun.interview.auth.controller;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.auth.dto.AuthResponse;
import com.linrun.interview.auth.dto.LoginRequest;
import com.linrun.interview.auth.dto.RegisterRequest;
import com.linrun.interview.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器：统一 v1 会话入口。
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/v1/auth")
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

    @Operation(summary = "注销当前会话")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    @Operation(summary = "获取当前用户")
    @GetMapping("/me")
    public Result<UserResponse> me() {
        var user = authService.currentUser();
        return Result.success(new UserResponse(
            user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName(),
            user.getRole() == null ? "USER" : user.getRole().name()));
    }

    public record UserResponse(
        Long id,
        String username,
        String email,
        String displayName,
        String role
    ) {}
}
