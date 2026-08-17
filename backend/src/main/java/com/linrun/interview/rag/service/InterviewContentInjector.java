package com.linrun.interview.rag.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;

import java.util.List;

/**
 * 把检索片段注入成「参考资料」而不是英文 generic context，避免生成模型把对话上下文当成依据。
 */
public final class InterviewContentInjector implements ContentInjector {

    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage chatMessage) {
        String question = extractQuestion(chatMessage);
        StringBuilder body = new StringBuilder();
        body.append("用户问题：").append(question).append("\n\n");
        body.append("参考资料：\n");
        if (contents != null) {
            for (int i = 0; i < contents.size(); i++) {
                String text = contents.get(i).textSegment().text();
                if (text == null || text.isBlank()) {
                    continue;
                }
                body.append("[").append(i + 1).append("]\n");
                body.append(sanitizeForGeneration(text)).append("\n\n");
            }
        }
        body.append("只根据上述参考资料回答用户问题。不要补充参考资料之外的内容，不要写小贴士，不要展开成教程。");
        return UserMessage.from(body.toString());
    }

    /** 去掉 MinerU 图片和裸 URL，避免模型把图注或相邻节标题写成答案。 */
    static String sanitizeForGeneration(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
            .replaceAll("(?i)<img[^>]*>", " ")
            .replaceAll("https?://\\S+", " ")
            .replaceAll("[ \\t\\x0B\\f]+", " ")
            .replaceAll("(?m)^ +| +$", "")
            .replaceAll("\\n{3,}", "\n\n")
            .strip();
    }

    private static String extractQuestion(ChatMessage chatMessage) {
        if (chatMessage instanceof UserMessage userMessage) {
            return userMessage.singleText();
        }
        return chatMessage == null ? "" : chatMessage.toString();
    }
}
