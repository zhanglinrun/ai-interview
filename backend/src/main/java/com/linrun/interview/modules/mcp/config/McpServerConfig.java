package com.linrun.interview.modules.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.evaluation.UnifiedEvaluationService;
import com.linrun.interview.modules.interview.service.InterviewPersistenceService;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryService;
import com.linrun.interview.modules.mcp.security.McpApiKeyAuthFilter;
import com.linrun.interview.modules.mcp.tool.EvaluationMcpTool;
import com.linrun.interview.modules.mcp.tool.InterviewHistoryMcpTool;
import com.linrun.interview.modules.mcp.tool.KnowledgeBaseMcpTool;
import com.linrun.interview.modules.mcp.tool.ResumeMcpTool;
import com.linrun.interview.modules.resume.service.ResumePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * MCP Server 装配：4 个业务工具 + API Key 鉴权过滤器。
 *
 * <p>整体由 {@code app.mcp.enabled}（环境变量 APP_MCP_ENABLED）门控，与
 * {@code spring.ai.mcp.server.enabled} 共用同一变量，保证端点与工具同开同关。
 * 工具经 {@link ToolCallbackProvider} 注册进 Spring AI MCP Server
 * 自动装配（SSE transport：GET /sse 建流，POST /mcp/message 收 JSON-RPC）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public class McpServerConfig {

    @Bean
    public KnowledgeBaseMcpTool knowledgeBaseMcpTool(KnowledgeBaseQueryService queryService,
                                                     KnowledgeBaseEntityMapper knowledgeBaseEntityMapper,
                                                     McpServerProperties properties) {
        return new KnowledgeBaseMcpTool(queryService, knowledgeBaseEntityMapper, properties);
    }

    @Bean
    public ResumeMcpTool resumeMcpTool(ResumePersistenceService resumePersistenceService,
                                       McpServerProperties properties) {
        return new ResumeMcpTool(resumePersistenceService, properties);
    }

    @Bean
    public InterviewHistoryMcpTool interviewHistoryMcpTool(
            InterviewPersistenceService interviewPersistenceService,
            McpServerProperties properties) {
        return new InterviewHistoryMcpTool(interviewPersistenceService, properties);
    }

    @Bean
    public EvaluationMcpTool evaluationMcpTool(UnifiedEvaluationService unifiedEvaluationService,
                                               LlmProviderRegistry llmProviderRegistry,
                                               McpServerProperties properties) {
        return new EvaluationMcpTool(unifiedEvaluationService, llmProviderRegistry, properties);
    }

    @Bean
    public ToolCallbackProvider interviewMcpToolCallbacks(KnowledgeBaseMcpTool knowledgeBaseMcpTool,
                                                          ResumeMcpTool resumeMcpTool,
                                                          InterviewHistoryMcpTool interviewHistoryMcpTool,
                                                          EvaluationMcpTool evaluationMcpTool) {
        log.info("[McpServer] 注册 MCP 工具: search_kb, read_resume, list_history, evaluate_answer");
        return MethodToolCallbackProvider.builder()
            .toolObjects(knowledgeBaseMcpTool, resumeMcpTool, interviewHistoryMcpTool, evaluationMcpTool)
            .build();
    }

    @Bean
    public FilterRegistrationBean<McpApiKeyAuthFilter> mcpApiKeyAuthFilterRegistration(
            McpServerProperties properties, ObjectMapper objectMapper) {
        FilterRegistrationBean<McpApiKeyAuthFilter> registration =
            new FilterRegistrationBean<>(new McpApiKeyAuthFilter(properties, objectMapper));
        // 覆盖 SSE 建流端点与 JSON-RPC 消息端点（对应 spring.ai.mcp.server.sse-*-endpoint 默认值）
        registration.addUrlPatterns("/sse", "/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("mcpApiKeyAuthFilter");
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.warn("[McpServer] app.mcp.api-key 未配置，MCP 端点将拒绝所有请求（fail-closed）");
        }
        return registration;
    }
}
