package com.linrun.interview.business.service;

import com.linrun.interview.business.vo.InterviewQuestionDTO;
import com.linrun.interview.business.vo.InterviewReportDTO;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;

/** 面试回答评估端口，隔离 LLM Judge 与报告 DTO 适配。 */
public interface InterviewEvaluationPort {
    InterviewReportDTO evaluateInterview(ChatModel chatModel, String sessionId,
                                         String resumeText, List<InterviewQuestionDTO> questions);
}
