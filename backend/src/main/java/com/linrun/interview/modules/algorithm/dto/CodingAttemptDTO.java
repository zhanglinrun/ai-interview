package com.linrun.interview.modules.algorithm.dto;

import com.linrun.interview.modules.algorithm.model.CodingAttemptMode;
import com.linrun.interview.modules.algorithm.model.CodingAttemptStatus;
import com.linrun.interview.modules.algorithm.model.CodingLanguage;
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
