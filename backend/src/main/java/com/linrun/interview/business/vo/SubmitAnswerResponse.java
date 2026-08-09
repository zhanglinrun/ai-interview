package com.linrun.interview.business.vo;

/**
 * 提交答案响应
 */
public record SubmitAnswerResponse(
    boolean hasNextQuestion,
    InterviewQuestionDTO nextQuestion,
    int currentIndex,
    int totalQuestions,
    long sessionVersion
) {
    public SubmitAnswerResponse(boolean hasNextQuestion, InterviewQuestionDTO nextQuestion,
                                int currentIndex, int totalQuestions) {
        this(hasNextQuestion, nextQuestion, currentIndex, totalQuestions, 0L);
    }
}
