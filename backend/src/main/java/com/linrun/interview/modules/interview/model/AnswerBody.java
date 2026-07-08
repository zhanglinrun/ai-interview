package com.linrun.interview.modules.interview.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交/暂存答案的请求体（sessionId 走路径参数，questionIndex/answer 走 body）。
 * 独立于 {@link SubmitAnswerRequest}，用于 Controller 层承接 body 并触发 Bean Validation，
 * 取代原先的裸 {@code Map<String, Object>}（会绕过校验且强转 NPE/ClassCastException）。
 */
public record AnswerBody(
    @NotNull(message = "问题索引不能为空")
    @Min(value = 0, message = "问题索引无效")
    Integer questionIndex,

    @NotBlank(message = "答案不能为空")
    String answer
) {}
