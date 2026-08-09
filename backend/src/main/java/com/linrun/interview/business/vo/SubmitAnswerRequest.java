package com.linrun.interview.business.vo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交答案请求
 */
public record SubmitAnswerRequest(
    @NotBlank(message = "会话ID不能为空")
    String sessionId,

    @NotBlank(message = "命令 ID 不能为空")
    @jakarta.validation.constraints.Size(max = 64, message = "命令 ID 过长")
    String commandId,

    @NotNull(message = "会话版本不能为空")
    @Min(value = 0, message = "会话版本不能为负数")
    Long expectedSessionVersion,
    
    @NotNull(message = "问题索引不能为空")
    @Min(value = 0, message = "问题索引无效")
    Integer questionIndex,
    
    @NotBlank(message = "答案不能为空")
    String answer
) {
    public SubmitAnswerRequest(String sessionId, Integer questionIndex, String answer) {
        this(sessionId, "legacy-" + java.util.UUID.randomUUID(), 0L, questionIndex, answer);
    }
}
