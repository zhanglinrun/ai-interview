package com.linrun.interview.business.client;

import com.linrun.interview.business.constant.CodingLanguage;

public record JudgeRequest(
    String requestId,
    CodingLanguage language,
    String sourceCode,
    String expectedOutput,
    int totalCount
) {
}
