package interview.guide.modules.interview.agent.tool;

import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库检索工具：面试官 Agent 出题前用它了解岗位要点。
 * <p>
 * 直接复用第 1 步的混合检索（向量 + 关键词 RRF 融合），是 agent 真实可调用的工具，
 * 而非写死流程。模型自行决定是否检索、检索什么。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseSearchTool implements AgentTool {

    private static final int TOP_K = 6;
    private static final double MIN_SCORE = 0.2;
    private static final int MAX_SNIPPET_CHARS = 300;
    private static final int MAX_OBSERVATION_DOCS = 4;

    private final KnowledgeBaseVectorService vectorService;

    @Override
    public String name() {
        return "search_knowledge_base";
    }

    @Override
    public String description() {
        return "检索岗位知识库，了解该方向的考点、技术要求与评判标准。"
            + "输入是要检索的关键词或问题。当你需要依据岗位资料出题或判断追问方向时调用。";
    }

    @Override
    public String execute(String input, AgentToolContext context) {
        if (input == null || input.isBlank()) {
            return "未提供检索关键词，无法检索知识库。";
        }
        if (context.knowledgeBaseIds().isEmpty()) {
            return "本次面试未关联岗位知识库，无可检索内容。请基于通用标准出题。";
        }

        try {
            List<Document> docs = vectorService.hybridSearch(
                input, context.knowledgeBaseIds(), TOP_K, MIN_SCORE);
            if (docs.isEmpty()) {
                return "知识库中未检索到与「" + input + "」相关的内容。";
            }
            return docs.stream()
                .limit(MAX_OBSERVATION_DOCS)
                .map(doc -> "- " + truncate(doc.getText()))
                .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("[KnowledgeBaseSearchTool] 检索失败: {}", e.getMessage());
            return "知识库检索出错，请基于通用标准继续。";
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_SNIPPET_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_SNIPPET_CHARS) + "...";
    }
}
