package com.linrun.interview.modules.mcp.tool;

import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryService;
import com.linrun.interview.modules.mcp.config.McpServerProperties;
import com.linrun.interview.modules.mcp.support.McpUserContexts;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Arrays;
import java.util.List;

/**
 * MCP 工具：知识库检索。
 *
 * <p>委托 {@link KnowledgeBaseQueryService#retrieveForEvaluation}，与前端 RAG 查询、
 * 面试 Agent 出题共用同一条「改写 → 混合检索 → rerank → 父子扩展」链路。
 * kbIds 先按 MCP 用户过滤归属，杜绝跨用户检索。
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeBaseMcpTool {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;
    private static final int MAX_SNIPPET_CHARS = 600;

    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;
    private final McpServerProperties mcpProperties;

    public record KbHit(String source, String snippet) {}

    public record KbSearchResult(String query, int hitCount, List<KbHit> hits, String message) {
        static KbSearchResult empty(String query, String message) {
            return new KbSearchResult(query, 0, List.of(), message);
        }
    }

    @Tool(name = "search_kb", description = "检索面试知识库：输入问题或关键词，"
        + "返回经混合检索与重排后最相关的知识片段。kbIds 留空时检索当前用户全部知识库。")
    public KbSearchResult searchKb(
            @ToolParam(description = "要检索的问题或关键词") String query,
            @ToolParam(description = "知识库ID，多个用英文逗号分隔；留空表示全部知识库",
                required = false) String kbIds,
            @ToolParam(description = "返回片段数上限，默认 5，最大 10", required = false) Integer topK) {
        if (query == null || query.isBlank()) {
            return KbSearchResult.empty(query, "检索关键词不能为空");
        }
        return McpUserContexts.runAs(mcpProperties.getUserId(), () -> doSearch(query, kbIds, topK));
    }

    private KbSearchResult doSearch(String query, String kbIds, Integer topK) {
        List<Long> accessibleIds = resolveAccessibleKbIds(kbIds);
        if (accessibleIds.isEmpty()) {
            return KbSearchResult.empty(query, "没有可访问的知识库（ID 不存在或不属于当前 MCP 用户）");
        }

        int limit = topK == null ? DEFAULT_TOP_K : Math.min(Math.max(topK, 1), MAX_TOP_K);
        try {
            List<TextSegment> segments = queryService.retrieveForEvaluation(accessibleIds, query);
            List<KbHit> hits = segments.stream()
                .limit(limit)
                .map(this::toHit)
                .toList();
            String message = hits.isEmpty() ? "知识库中未检索到相关内容" : null;
            return new KbSearchResult(query, hits.size(), hits, message);
        } catch (Exception e) {
            log.warn("[McpTool] search_kb 检索失败: query={}", query, e);
            return KbSearchResult.empty(query, "知识库检索失败: " + e.getMessage());
        }
    }

    /** 解析入参 kbIds 并校验归属；留空则取该用户全部知识库 */
    private List<Long> resolveAccessibleKbIds(String kbIds) {
        Long userId = UserContext.requireUserId();
        if (kbIds == null || kbIds.isBlank()) {
            return EntityQueries.listByUserId(knowledgeBaseEntityMapper, userId,
                    KnowledgeBaseEntity::getUserId)
                .stream().map(KnowledgeBaseEntity::getId).toList();
        }
        List<Long> requested = Arrays.stream(kbIds.split(","))
            .map(String::trim)
            .filter(s -> s.matches("\\d+"))
            .map(Long::valueOf)
            .toList();
        return EntityQueries.listByUserIdAndIdIn(knowledgeBaseEntityMapper, userId, requested,
                KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId)
            .stream().map(KnowledgeBaseEntity::getId).toList();
    }

    private KbHit toHit(TextSegment segment) {
        String source = segment.metadata() != null
            ? segment.metadata().getString(MetadataKeyConstant.FILE_NAME) : null;
        return new KbHit(source == null ? "未知来源" : source, truncate(segment.text()));
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_SNIPPET_CHARS
            ? normalized
            : normalized.substring(0, MAX_SNIPPET_CHARS) + "...";
    }
}
