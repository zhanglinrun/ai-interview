package com.linrun.interview.modules.algorithm.client;

import com.linrun.interview.modules.algorithm.model.CodingLanguage;

public record JudgeRequest(
    String requestId,
    CodingLanguage language,
    String sourceCode,
    String expectedOutput,
    int totalCount
) {
}
