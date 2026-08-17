package com.linrun.interview.business.vo;

import java.util.List;

/**
 * 单条已解释的 Agent 步骤，给回放页直接展示，而不是倒原始 input/observation。
 */
public record AgentTraceEventDTO(
    int step,
    Integer questionIndex,
    String role,
    String action,
    String state,
    String headline,
    String body,
    Boolean approved,
    Integer score,
    String retryHint,
    String followUpAction,
    String capability,
    List<String> evidenceIds,
    boolean reflexion,
    String input
) {}
