package com.linrun.interview.modules.dingtalk.callback;

import com.alibaba.fastjson2.JSONObject;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import com.linrun.interview.modules.dingtalk.service.DingTalkChatBridgeService;
import com.linrun.interview.modules.dingtalk.service.RobotGroupMessagesService;
import com.linrun.interview.modules.dingtalk.service.RobotPrivateMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 钉钉 Stream 机器人消息回调（对齐 know-engine ChatBotCallbackListener，面试域适配）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.dingtalk", name = "stream-enabled", havingValue = "true")
public class ChatBotCallbackListener implements OpenDingTalkCallbackListener<ChatbotMessage, JSONObject> {

  private final RobotGroupMessagesService robotGroupMessagesService;
  private final RobotPrivateMessageService robotPrivateMessageService;
  private final DingTalkChatBridgeService chatBridgeService;

  @Override
  public JSONObject execute(ChatbotMessage message) {
    try {
      MessageContent text = message.getText();
      if (text == null || text.getContent() == null || text.getContent().isBlank()) {
        return new JSONObject();
      }
      String msg = text.getContent().trim();
      String conversationType = message.getConversationType();
      String openConversationId = message.getConversationId();
      String senderStaffId = message.getSenderStaffId();
      log.info("[ChatBotCallbackListener] 收到消息: type={}, msg={}", conversationType, msg);

      if ("2".equals(conversationType)) {
        robotGroupMessagesService.sendText(openConversationId, "已接到您的请求，正在思考中...");
      } else if ("1".equals(conversationType)) {
        robotPrivateMessageService.sendText("已接到您的请求，正在思考中...", senderStaffId);
      }

      DingTalkChatBridgeService.DingTalkFormattedReply reply = chatBridgeService.chat(null, msg);

      if ("2".equals(conversationType)) {
        sendGroup(openConversationId, reply);
      } else if ("1".equals(conversationType)) {
        sendPrivate(senderStaffId, reply);
      }
      return new JSONObject();
    } catch (Exception e) {
      log.error("[ChatBotCallbackListener] 处理失败", e);
      return new JSONObject();
    }
  }

  private void sendGroup(String openConversationId, DingTalkChatBridgeService.DingTalkFormattedReply reply)
      throws Exception {
    if (reply.useMarkdown()) {
      robotGroupMessagesService.sendMarkdown(openConversationId, "智能问答", reply.markdown());
    } else {
      robotGroupMessagesService.sendText(openConversationId, reply.body());
    }
  }

  private void sendPrivate(String senderStaffId, DingTalkChatBridgeService.DingTalkFormattedReply reply)
      throws Exception {
    if (reply.useMarkdown()) {
      robotPrivateMessageService.sendMarkdown("智能问答", reply.markdown(), senderStaffId);
    } else {
      robotPrivateMessageService.sendText(reply.body(), senderStaffId);
    }
  }
}
