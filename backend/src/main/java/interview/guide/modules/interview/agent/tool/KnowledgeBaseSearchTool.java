package interview.guide.modules.interview.agent.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.segment.TextSegment;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库检索工具（LangChain4j @Tool 版）：面试官 Agent 出题前用它了解岗位要点。
 *
 * <p>替代原 {@code AgentTool} 接口实现：方法用 {@link Tool} 注解，由 LC4j 框架
 * 在 function-calling 时自动选择调用。工具上下文（knowledgeBaseIds）通过
 * {@link AgentContextHolder} 从 ThreadLocal 取，对模型透明。
 *
 * <p>检索走 {@link KnowledgeBaseVectorService#similaritySearch} 的 ES 向量通道
 * （原 hybridSearch 已随阶段5 RRF 简化移除）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseSearchTool {

    private static final int TOP_K = 6;
    private static final double MIN_SCORE = 0.2;
    private static final int MAX_SNIPPET_CHARS = 300;
    private static final int MAX_OBSERVATION_DOCS = 4;

    private final KnowledgeBaseVectorService vectorService;

    @Tool("检索岗位知识库，了解该方向的考点、技术要求与评判标准。"
        + "参数 query 是要检索的关键词或问题。当你需要依据岗位资料出题或判断追问方向时调用。")
    public String searchKnowledgeBase(@P("要检索的关键词或问题") String query) {
        AgentToolContext context = AgentContextHolder.get();
        if (query == null || query.isBlank()) {
            return "未提供检索关键词，无法检索知识库。";
        }
        if (context == null || context.knowledgeBaseIds().isEmpty()) {
            return "本次面试未关联岗位知识库，无可检索内容。请基于通用标准出题。";
        }

        try {
            List<TextSegment> docs = vectorService.similaritySearch(
                query, context.knowledgeBaseIds(), TOP_K, MIN_SCORE);
            if (docs.isEmpty()) {
                return "知识库中未检索到与「" + query + "」相关的内容。";
            }
            return docs.stream()
                .limit(MAX_OBSERVATION_DOCS)
                .map(segment -> "- " + truncate(segment.text()))
                .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("[KnowledgeBaseSearchTool] 检索失败: {}", e.getMessage(), e);
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
