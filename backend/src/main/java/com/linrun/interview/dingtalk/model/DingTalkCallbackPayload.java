package com.linrun.interview.dingtalk.model;

import com.fasterxml.jackson.databind.JsonNode;

/** 钉钉机器人回调的最小稳定契约，额外字段由 Jackson 忽略。 */
public record DingTalkCallbackPayload(
    String msgId,
    Long createAt,
    String conversationId,
    String conversationType,
    String senderStaffId,
    String senderNick,
    String sessionWebhook,
    Long sessionWebhookExpiredTime,
    JsonNode text,
    JsonNode raw
) {
    public String textContent() {
        if (text == null || text.isNull()) {
            return "";
        }
        if (text.isTextual()) {
            return text.asText();
        }
        JsonNode content = text.get("content");
        return content == null || content.isNull() ? "" : content.asText("");
    }
}
