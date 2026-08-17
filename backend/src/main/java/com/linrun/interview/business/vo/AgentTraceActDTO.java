package com.linrun.interview.business.vo;

import java.util.List;

/**
 * 一次 PLANNING 或一题的 ASKING/CRITIQUING 回放块。
 */
public record AgentTraceActDTO(
    Integer questionIndex,
    String title,
    List<String> statePath,
    int reflexionRounds,
    String finalQuestion,
    String followUpAction,
    Boolean criticApproved,
    List<AgentTraceEventDTO> events
) {}
