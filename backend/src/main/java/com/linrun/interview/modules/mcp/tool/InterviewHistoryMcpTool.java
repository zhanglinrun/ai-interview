package com.linrun.interview.modules.mcp.tool;

import com.linrun.interview.modules.interview.model.SessionListItemDTO;
import com.linrun.interview.modules.interview.service.InterviewPersistenceService;
import com.linrun.interview.modules.mcp.config.McpServerProperties;
import com.linrun.interview.modules.mcp.support.McpUserContexts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * MCP 工具：面试历史列表。复用 {@link SessionListItemDTO}，与
 * {@code GET /api/interview/sessions} 返回同构（按 userId 隔离、创建时间倒序）。
 */
@Slf4j
@RequiredArgsConstructor
public class InterviewHistoryMcpTool {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final InterviewPersistenceService interviewPersistenceService;
    private final McpServerProperties mcpProperties;

    @Tool(name = "list_history", description = "查询当前用户的面试历史（按创建时间倒序）："
        + "会话ID、面试方向、难度、题数、状态、评估状态与总分。")
    public List<SessionListItemDTO> listHistory(
            @ToolParam(description = "返回条数上限，默认 10，最大 50", required = false) Integer limit) {
        int capped = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        return McpUserContexts.runAs(mcpProperties.getUserId(),
            () -> interviewPersistenceService.findAll().stream()
                .limit(capped)
                .map(SessionListItemDTO::from)
                .toList());
    }
}
