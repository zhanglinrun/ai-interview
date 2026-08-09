package com.linrun.interview.business.service;

import java.util.Set;

public record ToolDescriptor(
    String name,
    Set<String> allowedRoles,
    boolean cacheable,
    boolean idempotent,
    long timeoutMs,
    int maxInputChars,
    String version
) {
  public ToolDescriptor {
    allowedRoles = allowedRoles == null ? Set.of() : Set.copyOf(allowedRoles);
    timeoutMs = timeoutMs <= 0 ? 3_000L : timeoutMs;
    maxInputChars = maxInputChars <= 0 ? 4_000 : maxInputChars;
    version = version == null || version.isBlank() ? "1" : version;
  }
}
