package com.linrun.interview.business.vo;

import com.linrun.interview.business.vo.TurnDecision.AnswerSignals;

import java.util.List;

/**
 * 面试问题DTO
 * type 由 Skill category key 驱动（如 MYSQL、CSS、DYNAMIC_PROGRAMMING 等），不再使用枚举
 */
public record InterviewQuestionDTO(
    int questionIndex,
    String question,
    String type,           // Skill category key，如 "MYSQL"、"CSS"、"DP"
    String category,       // 展示用标签，如 "MySQL"、"CSS"、"动态规划"
    String topicSummary,   // 知识点摘要，如 "Redis RDB/AOF 持久化对比"，用于历史去重压缩
    String userAnswer,
    Integer score,
    String feedback,
    boolean isFollowUp,
    Integer parentQuestionIndex,
    String capabilityAtomId,
    String followUpAction,
    List<String> evidenceIds,
    /** 主问题作答后沉淀的结构信号；追问可空，供本场摘要列表使用 */
    AnswerSignals answerSignals,
    /** Critic 是否通过；达上限短路时为 false，评估时降权 */
    Boolean criticApproved
) {
    public InterviewQuestionDTO {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    public static InterviewQuestionDTO create(int index, String question, String type, String category) {
        return new InterviewQuestionDTO(index, question, type, category, null, null, null, null,
            false, null, null, null, List.of(), null, null);
    }

    public static InterviewQuestionDTO create(int index, String question, String type, String category,
                                               String topicSummary, boolean isFollowUp, Integer parentQuestionIndex) {
        return new InterviewQuestionDTO(index, question, type, category, topicSummary, null, null, null,
            isFollowUp, parentQuestionIndex, null, null, List.of(), null, null);
    }

    public static InterviewQuestionDTO createAgent(int index, String question, String type,
                                                    String category, String topicSummary,
                                                    boolean isFollowUp, Integer parentQuestionIndex,
                                                    String capabilityAtomId, String followUpAction,
                                                    List<String> evidenceIds) {
        return createAgent(index, question, type, category, topicSummary, isFollowUp,
            parentQuestionIndex, capabilityAtomId, followUpAction, evidenceIds, null);
    }

    public static InterviewQuestionDTO createAgent(int index, String question, String type,
                                                    String category, String topicSummary,
                                                    boolean isFollowUp, Integer parentQuestionIndex,
                                                    String capabilityAtomId, String followUpAction,
                                                    List<String> evidenceIds, Boolean criticApproved) {
        return new InterviewQuestionDTO(index, question, type, category, topicSummary, null, null, null,
            isFollowUp, parentQuestionIndex, capabilityAtomId, followUpAction, evidenceIds, null,
            criticApproved);
    }

    public InterviewQuestionDTO withAnswer(String answer) {
        return new InterviewQuestionDTO(questionIndex, question, type, category, topicSummary, answer,
            score, feedback, isFollowUp, parentQuestionIndex, capabilityAtomId, followUpAction,
            evidenceIds, answerSignals, criticApproved);
    }

    public InterviewQuestionDTO withAnswerSignals(AnswerSignals signals) {
        return new InterviewQuestionDTO(questionIndex, question, type, category, topicSummary, userAnswer,
            score, feedback, isFollowUp, parentQuestionIndex, capabilityAtomId, followUpAction,
            evidenceIds, signals, criticApproved);
    }

    public InterviewQuestionDTO withEvaluation(int score, String feedback) {
        return new InterviewQuestionDTO(questionIndex, question, type, category, topicSummary,
            userAnswer, score, feedback, isFollowUp, parentQuestionIndex, capabilityAtomId,
            followUpAction, evidenceIds, answerSignals, criticApproved);
    }
}
