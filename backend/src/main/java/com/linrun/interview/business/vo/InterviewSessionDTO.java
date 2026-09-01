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
        READY,        // 会话已准备
        IN_PROGRESS,  // 面试进行中
        PAUSED,       // 面试已暂停
        COMPLETING,   // 面试正在收尾
        COMPLETED,    // 面试已完成
        EVALUATED,    // 已生成评估报告
        ABORTED,      // 会话已中止
        FAILED        // 会话运行失败
    }
}
