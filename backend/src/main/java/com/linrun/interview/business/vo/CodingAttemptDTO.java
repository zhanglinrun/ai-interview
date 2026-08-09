package com.linrun.interview.business.vo;

import com.linrun.interview.business.constant.CodingAttemptMode;
import com.linrun.interview.business.constant.CodingAttemptStatus;
import com.linrun.interview.business.constant.CodingLanguage;
import java.time.LocalDateTime;

public record CodingAttemptDTO(
    String attemptId,
    Long problemVersionId,
    CodingAttemptMode mode,
    String contextId,
    CodingLanguage language,
    CodingAttemptStatus status,
    LocalDateTime startedAt,
    LocalDateTime submittedAt,
    LocalDateTime completedAt
) {
}
