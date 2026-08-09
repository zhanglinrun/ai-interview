package com.linrun.interview.dingtalk.service;

import com.linrun.interview.dingtalk.client.DingTalkRobotClient;
import com.linrun.interview.dingtalk.config.DingTalkProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 机器人出站应用服务，统一应用配置和回调临时 webhook。 */
@Service
@RequiredArgsConstructor
public class DingTalkRobotService {

    private final DingTalkRobotClient robotClient;
    private final DingTalkProperties properties;

    public void send(String webhook, String secret, String content, String atUserId) {
        String targetWebhook = StringUtils.hasText(webhook) ? webhook : properties.getRobotWebhook();
        String targetSecret = StringUtils.hasText(secret) ? secret : properties.getRobotSecret();
        robotClient.sendText(targetWebhook, targetSecret, content, atUserId);
    }
}
