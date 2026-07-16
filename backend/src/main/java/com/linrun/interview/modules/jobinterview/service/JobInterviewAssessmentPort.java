package com.linrun.interview.modules.jobinterview.service;

import com.linrun.interview.modules.jobinterview.model.AnswerAssessment;
import com.linrun.interview.modules.jobinterview.model.JobInterviewQuestionEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewSessionEntity;

/** 外部 LLM 评价边界；调用方保证不在数据库事务中执行。 */
public interface JobInterviewAssessmentPort {

  AssessmentOutcome assess(
      Long userId,
      JobInterviewSessionEntity session,
      JobInterviewQuestionEntity question,
      String answer
  );

  ClarificationOutcome clarify(
      Long userId,
      JobInterviewSessionEntity session,
      JobInterviewQuestionEntity question,
      String candidateQuestion
  );

  record AssessmentOutcome(
      AnswerAssessment assessment,
      String followUpQuestion,
      long latencyMs,
      Integer inputTokens,
      Integer outputTokens,
      int retryCount,
      String degradedReason
  ) {
  }

  record ClarificationOutcome(
      String message,
      long latencyMs,
      Integer inputTokens,
      Integer outputTokens,
      int retryCount,
      String degradedReason
  ) {
  }
}
