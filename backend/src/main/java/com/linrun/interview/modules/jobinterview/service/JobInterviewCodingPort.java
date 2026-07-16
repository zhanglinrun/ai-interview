package com.linrun.interview.modules.jobinterview.service;

import com.linrun.interview.modules.jobinterview.model.JobCodingLanguage;
import com.linrun.interview.modules.jobinterview.model.JobInterviewQuestionEntity;

/** Hot 100/Judge0 的岗位实战适配边界；调用方保证不在数据库事务中执行。 */
public interface JobInterviewCodingPort {

  CodeTemplate starter(
      JobInterviewQuestionEntity question,
      JobCodingLanguage language
  );

  CodingOutcome submit(
      Long userId,
      String sessionId,
      JobInterviewQuestionEntity question,
      JobCodingLanguage language,
      String commandId,
      String sourceCode
  );

  record CodingOutcome(
      String submissionId,
      String status,
      Integer passedCount,
      Integer totalCount,
      String diagnostic,
      Long timeMs,
      Long memoryKb,
      String failureCode,
      boolean pendingRejudge
  ) {
    public static CodingOutcome unavailable(String code, String diagnostic) {
      return new CodingOutcome(
          null, "UNAVAILABLE", 0, 0, diagnostic, null, null, code, true);
    }
  }

  record CodeTemplate(String sourceCode, String functionSignature) {
    public CodeTemplate {
      sourceCode = sourceCode == null ? "" : sourceCode;
      functionSignature = functionSignature == null ? "" : functionSignature;
    }
  }
}
