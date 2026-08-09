package com.linrun.interview.rag.service;

import com.linrun.interview.chat.dto.RagCardChoiceDTO;
import com.linrun.interview.rag.model.RagSourceDTO;

import java.util.List;

/**
 * RAG 引用与卡片 → Markdown 渲染（供 Web SSE 复用）。
 */
public final class RagReferenceMarkdownBuilder {

  private RagReferenceMarkdownBuilder() {
  }

  public static String buildAnswerWithReferences(String answer, List<RagSourceDTO> references) {
    if (references == null || references.isEmpty()) {
      return answer;
    }
    StringBuilder sb = new StringBuilder();
    if (answer != null && !answer.isBlank()) {
      sb.append(answer.trim());
    }
    sb.append("\n\n---\n**参考来源：**\n");
    int idx = 1;
    for (RagSourceDTO ref : references) {
      String title = firstNonBlank(ref.sourceName(), ref.documentTitle(), "来源" + idx);
      sb.append(idx).append(". ").append(escapeMarkdown(title));
      if (ref.similarity() != null) {
        sb.append("（相似度 ").append(String.format("%.2f", ref.similarity())).append("）");
      }
      sb.append("\n");
      idx++;
    }
    return sb.toString();
  }

  public static String buildChoiceMarkdown(String prompt, List<RagCardChoiceDTO> choices) {
    String header = (prompt != null && !prompt.isBlank()) ? prompt : "请选择";
    StringBuilder sb = new StringBuilder();
    sb.append("**").append(escapeMarkdown(header)).append("**\n\n");
    for (int i = 0; i < choices.size(); i++) {
      RagCardChoiceDTO choice = choices.get(i);
      sb.append(i + 1).append(". ").append(escapeMarkdown(choice.label()));
      if (choice.type() != null && !choice.type().isBlank()) {
        sb.append(" `").append(choice.type()).append("`");
      }
      sb.append("\n");
    }
    sb.append("\n请回复「选择第N个」或直接说明选项名称。");
    return sb.toString();
  }

  public static String escapeMarkdown(String text) {
    if (text == null) {
      return "";
    }
    return text.replace("\\", "\\\\")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("(", "\\(")
        .replace(")", "\\)")
        .replace("`", "\\`");
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }
}
