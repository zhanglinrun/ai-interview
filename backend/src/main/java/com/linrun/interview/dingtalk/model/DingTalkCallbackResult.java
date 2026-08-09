package com.linrun.interview.dingtalk.model;

/** 回调处理结果，供重试和监控区分已处理、重复和失败。 */
public record DingTalkCallbackResult(
    String messageId,
    String status,
    String answer,
    String traceId
) {
    public static DingTalkCallbackResult duplicate(String messageId) {
        return new DingTalkCallbackResult(messageId, "DUPLICATE", "", null);
    }
}
