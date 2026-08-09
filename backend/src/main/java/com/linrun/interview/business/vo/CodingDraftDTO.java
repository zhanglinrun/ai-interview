package com.linrun.interview.business.vo;

import com.linrun.interview.business.constant.CodingLanguage;
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
