package com.linrun.interview.modules.dingtalk.service;

import com.linrun.interview.modules.dingtalk.config.DingTalkProperties;
import com.linrun.interview.modules.dingtalk.util.DingTalkSignatureUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 钉钉 Webhook 回调服务（自定义机器人 HTTP 模式，Stream 不可用时的备选）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.dingtalk", name = "enabled", havingValue = "true")
public class DingTalkBotService {

  private final DingTalkProperties properties;
  private final DingTalkChatBridgeService chatBridgeService;

  public String chatAndCollect(List<Long> knowledgeBaseIds, String question) {
    return chatBridgeService.chat(knowledgeBaseIds, question).body();
  }

  public boolean verifySignature(String timestamp, String sign) {
    String secret = properties.getWebhookToken();
    if (secret == null || secret.isBlank()) {
      log.warn("[DingTalkBotService] webhookToken 未配置，拒绝回调");
      return false;
    }
    if (timestamp == null || timestamp.isBlank()) {
      return false;
    }
    boolean ok = DingTalkSignatureUtils.verify(timestamp, sign, secret);
    if (!ok) {
      log.warn("[DingTalkBotService] 签名校验失败: ts={}", timestamp);
    }
    return ok;
  }
}
