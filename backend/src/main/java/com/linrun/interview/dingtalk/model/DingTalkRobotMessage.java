package com.linrun.interview.dingtalk.model;

import jakarta.validation.constraints.NotBlank;

/** 主动发送钉钉机器人文本消息的请求。 */
public record DingTalkRobotMessage(
    @NotBlank String webhook,
    String secret,
    @NotBlank String content,
    String atUserId
) {
}
