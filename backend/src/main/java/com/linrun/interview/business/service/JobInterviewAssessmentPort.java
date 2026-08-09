package com.linrun.interview.business.service;

import com.linrun.interview.business.vo.AnswerAssessment;
import com.linrun.interview.business.entity.JobInterviewQuestionEntity;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;

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
