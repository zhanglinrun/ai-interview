package com.linrun.interview.modules.user.model;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    Long userId,
    String username,
    String displayName
) {}
