package com.linrun.interview.business.vo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Autosave payload; draft writes intentionally do not participate in command idempotency. */
public record DraftAnswerBody(
    @NotNull(message = "问题索引不能为空") @Min(value = 0, message = "问题索引无效")
    Integer questionIndex,
    @NotBlank(message = "答案不能为空") String answer
) {
}
