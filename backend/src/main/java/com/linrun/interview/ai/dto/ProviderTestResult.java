package com.linrun.interview.ai.dto;

import lombok.Builder;

@Builder
public record ProviderTestResult(
    boolean success,
    String message,
    String model
) {}
