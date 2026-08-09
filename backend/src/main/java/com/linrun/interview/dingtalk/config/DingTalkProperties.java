package com.linrun.interview.dingtalk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 钉钉开放平台和机器人适配配置。敏感值只从环境变量注入。 */
@Data
@ConfigurationProperties(prefix = "app.dingtalk")
public class DingTalkProperties {

    private boolean enabled;
    private String appKey = "";
    private String appSecret = "";
    private String verificationToken = "";
    private String aesKey = "";
    private String robotWebhook = "";
    private String robotSecret = "";
    private long replayWindowSeconds = 300;
    private int maxQuestionChars = 2000;
    private Long fallbackUserId = 1L;
    private List<Long> defaultKnowledgeBaseIds = new ArrayList<>();
}
