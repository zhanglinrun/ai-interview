package com.linrun.interview.business.client;

import com.linrun.interview.business.constant.JudgeStatus;

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
