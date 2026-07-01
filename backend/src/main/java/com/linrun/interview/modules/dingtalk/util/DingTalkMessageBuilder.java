package com.linrun.interview.modules.dingtalk.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 钉钉机器人消息体构造工具（对齐 know-engine DingTalkMessageBuilder）。
 */
public final class DingTalkMessageBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private DingTalkMessageBuilder() {
  }

  /** 文本消息：msgKey = sampleText */
  public static String textJson(String content) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("content", content);
    return node.toString();
  }

  /** Markdown 消息：msgKey = sampleMarkdown */
  public static String markdownJson(String title, String text) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("title", title);
    node.put("text", text);
    return node.toString();
  }

  /** ActionCard 消息：msgKey = sampleActionCard */
  public static String actionCardJson(String title, String text, String singleTitle, String singleUrl) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("title", title);
    node.put("text", text);
    node.put("singleTitle", singleTitle);
    node.put("singleURL", singleUrl);
    return node.toString();
  }
}
