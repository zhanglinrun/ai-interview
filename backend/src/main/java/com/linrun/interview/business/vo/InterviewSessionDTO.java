package com.linrun.interview.business.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试会话DTO
 */
public record InterviewSessionDTO(
    String sessionId,
    String resumeText,
    int totalQuestions,
    int currentQuestionIndex,
    List<InterviewQuestionDTO> questions,
    SessionStatus status,
    long sessionVersion,
    LocalDateTime createdAt
) {
    public InterviewSessionDTO(String sessionId, String resumeText, int totalQuestions,
                               int currentQuestionIndex, List<InterviewQuestionDTO> questions,
                               SessionStatus status) {
        this(sessionId, resumeText, totalQuestions, currentQuestionIndex, questions, status, 0L, null);
    }
    public enum SessionStatus {
        CREATED,      // 会话已创建
        READY,        // 岗位实战已准备
        IN_PROGRESS,  // 面试进行中
        PAUSED,       // 岗位实战已暂停
        COMPLETING,   // 岗位实战正在收尾
        COMPLETED,    // 面试已完成
        EVALUATED,    // 已生成旧版评估报告
        ABORTED,      // 岗位实战已中止
        FAILED        // 岗位实战失败
    }
}
