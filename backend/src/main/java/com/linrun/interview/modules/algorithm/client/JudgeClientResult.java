package com.linrun.interview.modules.algorithm.client;

import com.linrun.interview.modules.algorithm.model.JudgeStatus;

public record JudgeClientResult(
    String providerSubmissionId,
    JudgeStatus status,
    int passedCount,
    int totalCount,
    String diagnostic,
    Long timeMs,
    Long memoryKb,
    String failureCode
) {
  public static JudgeClientResult unavailable(int totalCount, String code, String diagnostic) {
    return new JudgeClientResult(
        null, JudgeStatus.UNAVAILABLE, 0, totalCount, diagnostic, null, null, code);
  }
}
