package com.linrun.interview.document.service.impl;

import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.document.config.MineruProperties;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 调用视觉模型为图片生成 alt 文本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageDescriptionService {

 private static final String PROMPT =
 "请描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，"
 + "直接输出纯文本描述，不要多余说明，不要增加任何特殊符号，特别是换行符";

 private final LlmProviderRegistry llmProviderRegistry;
 private final MineruProperties mineruProperties;

 /**
 * @return 图片描述；失败时返回 null，调用方保留原 alt
 */
 public String describe(String imageUrl) {
 if (!mineruProperties.isVisionAltEnabled()
 || imageUrl == null || imageUrl.isBlank()) {
 return null;
 }
 try {
 ChatModel chatModel = llmProviderRegistry.getChatModelWithModel(
 null, mineruProperties.getVisionModel());
 UserMessage message = UserMessage.from(
 new TextContent(PROMPT),
 new ImageContent(imageUrl));
 String text = chatModel.chat(message).aiMessage().text();
 if (text == null || text.isBlank()) {
 return null;
 }
 return text.replace('\n', ' ').replace('\r', ' ').trim();
 } catch (Exception e) {
 log.warn("视觉模型生成图片描述失败: url={}, error={}", imageUrl, e.getMessage());
 return null;
 }
 }
}
