package com.linrun.interview.dingtalk.client;

/** 钉钉机器人出站端口，业务层不直接依赖 HTTP 实现。 */
public interface DingTalkRobotClient {
    void sendText(String webhook, String secret, String content, String atUserId);
}
