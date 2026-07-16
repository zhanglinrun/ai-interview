package com.linrun.interview.modules.algorithm.dto;

import com.linrun.interview.modules.algorithm.model.CodingLanguage;
import java.time.LocalDateTime;

/** 草稿恢复专用响应；判题结果响应绝不回传完整源码。 */
public record CodingDraftDTO(
    String attemptId,
    CodingLanguage language,
    String sourceCode,
    Integer revision,
    LocalDateTime updatedAt
) {
}
