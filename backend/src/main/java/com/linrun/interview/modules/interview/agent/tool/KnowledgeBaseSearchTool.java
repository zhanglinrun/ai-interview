package com.linrun.interview.modules.interview.agent.tool;

import com.linrun.interview.modules.interview.agent.model.InterviewEvidence;
import com.linrun.interview.modules.interview.agent.model.InterviewEvidence.Bundle;
import com.linrun.interview.modules.interview.service.InterviewKnowledgeRetrievalService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库检索工具（LangChain4j @Tool 版）：面试官 Agent 出题前用它了解岗位要点。
 *
 * <p>方法用 {@link Tool} 注解，由 LC4j 框架在 function-calling 时自动选择调用。工具上下文
 * （knowledgeBaseIds）通过 {@link AgentContextHolder} 从 ThreadLocal 取，对模型透明。
 *
 * <p>检索复用面试证据链，返回 evidence ID、来源与片段，便于编排轨迹审计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseSearchTool {

    private final InterviewKnowledgeRetrievalService retrievalService;

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
            Bundle bundle = retrievalService.retrieveEvidence(context.knowledgeBaseIds(), query);
            if (bundle.promptEvidence().isEmpty()) {
                return "知识库中未检索到与「" + query + "」相关的内容。";
            }
            return bundle.promptEvidence().stream()
                .map(this::formatEvidence)
                .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("[KnowledgeBaseSearchTool] 检索失败: {}", e.getMessage(), e);
            return "知识库检索出错，请基于通用标准继续。";
        }
    }

    private String formatEvidence(InterviewEvidence evidence) {
        return "- [" + evidence.id() + "] " + evidence.source() + ": " + evidence.snippet();
    }
}
