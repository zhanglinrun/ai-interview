package com.linrun.interview.modules.interview.model;

import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.modules.interview.model.InterviewSessionEntity.SessionStatus;

import java.time.LocalDateTime;

/**
 * 面试会话列表项 DTO（轻量，不含题目/答案等大字段）
 */
public record SessionListItemDTO(
    String sessionId,
    String skillId,
    String difficulty,
    Long resumeId,
    int totalQuestions,
    SessionStatus status,
    AsyncTaskStatus evaluateStatus,
    String evaluateError,
    Integer overallScore,
    boolean jobInterview,
    Long jobDescriptionId,
    String currentStage,
    Long sessionVersion,
    LocalDateTime createdAt,
    LocalDateTime completedAt
) {
    public static SessionListItemDTO from(InterviewSessionEntity e) {
        return new SessionListItemDTO(
            e.getSessionId(),
            e.getSkillId(),
            e.getDifficulty(),
            e.getResumeId(),
            e.getTotalQuestions() != null ? e.getTotalQuestions() : 0,
            e.getStatus(),
            e.getEvaluateStatus(),
            e.getEvaluateError(),
            e.getOverallScore(),
            e.getPreparationRunId() != null && !e.getPreparationRunId().isBlank(),
            e.getJobDescriptionId(),
            e.getCurrentStage(),
            e.getSessionVersion(),
            e.getCreatedAt(),
            e.getCompletedAt()
        );
    }
}
