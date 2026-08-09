package com.linrun.interview.business.service;

import com.linrun.interview.business.vo.HistoricalQuestion;
import com.linrun.interview.business.vo.InterviewQuestionDTO;
import com.linrun.interview.business.service.InterviewTopic.Category;

import java.util.List;

/** 批量题目生成端口；Agent 出题由 orchestration 端口独立负责。 */
public interface InterviewQuestionPort {
    List<InterviewQuestionDTO> generateQuestionsForTopic(
        Long userId, String topicId, String difficulty, String resumeText,
        int questionCount, List<HistoricalQuestion> historicalQuestions,
        List<Category> customCategories, String jdText, List<Long> knowledgeBaseIds);
}
