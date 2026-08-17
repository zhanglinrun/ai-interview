package com.linrun.interview.business.vo;

import java.time.LocalDateTime;

/**
 * Agent Trace 会话目录项：带步骤数，避免回放页默认选中空会话。
 */
public record AgentTraceCatalogItemDTO(
    String sessionId,
    String label,
    String status,
    int totalQuestions,
    boolean orphanRun,
    boolean hasPlan,
    int stepCount,
    String lastState,
    LocalDateTime createdAt
) {}
