package com.linrun.interview.modules.dingtalk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 钉钉机器人配置（P2：可选接入，默认关闭）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.dingtalk")
public class DingTalkProperties {

  /** 是否启用钉钉 Stream / Webhook 回调 */
  private boolean enabled = false;

  /** 应用 AppKey / ClientId */
  private String appKey = "";

  /** 应用 AppSecret / ClientSecret */
  private String appSecret = "";

  /** 机器人编码（群聊 @ 场景） */
  private String robotCode = "";

  /** Webhook 签名 Token（自定义机器人 Secret） */
  private String webhookToken = "";

  /** 是否启用 Stream 模式（需配置 appKey/appSecret） */
  private boolean streamEnabled = false;

  /** 绑定的平台用户 ID（钉钉问答代其访问知识库） */
  private Long systemUserId;

  /** 默认知识库 ID 列表（逗号分隔） */
  private String defaultKnowledgeBaseIds = "";
}
