package com.linrun.interview.modules.mcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MCP Server 业务侧配置。
 *
 * <p>传输层（SSE 端点、server name/version）由 Spring AI starter 的
 * {@code spring.ai.mcp.server.*} 管理；本类只管鉴权与数据归属两件事。
 * 两处开关共用环境变量 {@code APP_MCP_ENABLED} 联动开闭。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.mcp")
public class McpServerProperties {

    /** 是否启用 MCP Server（工具注册 + 鉴权过滤器；需与 spring.ai.mcp.server.enabled 同开同关） */
    private boolean enabled = false;

    /** MCP 端点 API Key。为空时视为未配置，所有 MCP 请求一律 401（fail-closed） */
    private String apiKey = "";

    /**
     * API Key 映射的平台用户ID：MCP 工具以该用户身份读取数据。
     * 平台所有业务查询按 userId 隔离，此字段就是 MCP 通道的数据边界。
     */
    private Long userId;
}
