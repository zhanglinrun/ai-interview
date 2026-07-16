package com.linrun.interview.modules.algorithm.dto;

import com.linrun.interview.modules.algorithm.model.CodingLanguage;
import com.linrun.interview.modules.algorithm.model.JudgeStatus;
import com.linrun.interview.modules.algorithm.model.TestSuiteType;
import java.time.LocalDateTime;

/** 仅暴露客观结果，不暴露源码、测试驱动、隐藏用例或 provider 原始响应。 */
public record JudgeSubmissionDTO(
    String submissionId,
    String attemptId,
    TestSuiteType suiteType,
    CodingLanguage language,
    JudgeStatus status,
    Integer passedCount,
    Integer totalCount,
    String diagnostic,
    Long timeMs,
    Long memoryKb,
    String failureCode,
    boolean pendingRejudge,
    LocalDateTime submittedAt,
    LocalDateTime completedAt
) {
}
