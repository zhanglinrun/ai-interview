package com.linrun.interview.modules.dingtalk;

import com.linrun.interview.common.result.Result;
import com.linrun.interview.modules.dingtalk.config.DingTalkProperties;
import com.linrun.interview.modules.dingtalk.service.DingTalkBotService;
import com.linrun.interview.modules.dingtalk.service.DingTalkChatBridgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 钉钉自定义机器人 Webhook 回调（HTTP 模式，Stream 不可用时的备选）。
 */
@RestController
@RequestMapping("/api/dingtalk")
@RequiredArgsConstructor
@ConditionalOnBean(DingTalkBotService.class)
public class DingTalkWebhookController {

  private final DingTalkBotService dingTalkBotService;
  private final DingTalkChatBridgeService chatBridgeService;
  private final DingTalkProperties properties;

  @PostMapping("/webhook")
  public Result<Map<String, String>> webhook(
      @RequestHeader(value = "timestamp", required = false) String timestamp,
      @RequestHeader(value = "sign", required = false) String sign,
      @RequestBody Map<String, Object> body) {
    if (!properties.isEnabled()) {
      return Result.error("钉钉集成未启用");
    }
    if (!dingTalkBotService.verifySignature(timestamp, sign)) {
      return Result.error("签名校验失败");
    }
    String text = extractText(body);
    if (text == null || text.isBlank()) {
      return Result.success(Map.of("msgtype", "text", "text", "请输入您的问题。"));
    }
    DingTalkChatBridgeService.DingTalkFormattedReply reply =
        chatBridgeService.chat(List.of(), text);
    if (reply.useMarkdown()) {
      return Result.success(Map.of(
          "msgtype", "markdown",
          "title", "智能问答",
          "text", reply.markdown()));
    }
    return Result.success(Map.of("msgtype", "text", "text", reply.body()));
  }

  @SuppressWarnings("unchecked")
  private String extractText(Map<String, Object> body) {
    if (body == null) {
      return null;
    }
    Object textObj = body.get("text");
    if (textObj instanceof Map<?, ?> textMap) {
      Object content = textMap.get("content");
      return content != null ? content.toString().trim() : null;
    }
    Object content = body.get("content");
    return content != null ? content.toString().trim() : null;
  }
}
