package com.linrun.interview.auth.dto;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    Long userId,
    String username,
    String displayName,
    String role
) {}
