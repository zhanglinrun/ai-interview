package com.linrun.interview.modules.knowledgebase.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.linrun.interview.common.ai.PromptTemplate;
import com.linrun.interview.common.util.JsonUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CRAG 纠正式检索打分器（P2 Agentic RAG）。
 *
 * <p>对 rerank 后的 top-N 片段让小模型对照用户问题打分：
 * <ul>
 *   <li>{@code CORRECT}：至少一个片段能直接支撑回答 → 直接生成</li>
 *   <li>{@code AMBIGUOUS}：部分相关但不足以支撑完整回答 → 用 {@code correctedQuery}
 *       重检索一次（调用方硬上限 1，防循环）</li>
 *   <li>{@code INCORRECT}：全部无关 → 调用方走通用对话兜底并明确告知「知识库无据」（防幻觉）</li>
 * </ul>
 *
 * <p>打分失败/解析失败按 {@code CORRECT} 兜底，不阻断主链路。
 */
@Slf4j
public class CorrectiveRetrievalGrader {

    public enum Grade { CORRECT, AMBIGUOUS, INCORRECT }

    /** 打分结果：grade + 原因 + ambiguous 时的纠正查询。 */
    public record GradeResult(Grade grade, String reasoning, String correctedQuery) {}

    private final ChatModel chatModel;
    private final PromptTemplate promptTemplate;
    private final int gradeTopN;
    private final int snippetMaxChars;

    public CorrectiveRetrievalGrader(ChatModel chatModel, PromptTemplate promptTemplate,
                                     int gradeTopN, int snippetMaxChars) {
        this.chatModel = chatModel;
        this.promptTemplate = promptTemplate;
        this.gradeTopN = gradeTopN;
        this.snippetMaxChars = snippetMaxChars;
    }

    public GradeResult grade(String question, List<Content> contents) {
        try {
            String response = chatModel.chat(promptTemplate.render(Map.of(
                "question", question,
                "documents", formatDocuments(contents))));
            JsonNode node = JsonUtil.fixAndParse(response);
            Grade grade = parseGrade(node.path("grade").asText(""));
            String reasoning = node.path("reasoning").asText("");
            String correctedQuery = node.path("correctedQuery").asText("").trim();
            log.info("[CorrectiveRetrievalGrader] CRAG 打分: grade={}, reasoning={}", grade, reasoning);
            return new GradeResult(grade, reasoning, correctedQuery);
        } catch (Exception e) {
            log.warn("[CorrectiveRetrievalGrader] 打分失败，按 correct 兜底不阻断: {}", e.getMessage(), e);
            return new GradeResult(Grade.CORRECT, "打分失败兜底", "");
        }
    }

    private Grade parseGrade(String raw) {
        return switch (raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)) {
            case "ambiguous" -> Grade.AMBIGUOUS;
            case "incorrect" -> Grade.INCORRECT;
            default -> Grade.CORRECT;
        };
    }

    private String formatDocuments(List<Content> contents) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(gradeTopN, contents.size());
        for (int i = 0; i < limit; i++) {
            String text = contents.get(i).textSegment().text().replaceAll("\\s+", " ").trim();
            if (text.length() > snippetMaxChars) {
                text = text.substring(0, snippetMaxChars) + "...";
            }
            sb.append('[').append(i + 1).append("] ").append(text).append('\n');
        }
        return sb.toString();
    }
}
