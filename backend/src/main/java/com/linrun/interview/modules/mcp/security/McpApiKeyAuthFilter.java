package com.linrun.interview.modules.mcp.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.modules.mcp.config.McpServerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * MCP 端点 API Key 鉴权过滤器。
 *
 * <p>MCP 的 SSE/消息端点不在 {@code /api/**} 下，不经过 JwtInterceptor，
 * 由本过滤器独立鉴权。Key 支持三种携带方式（优先级从高到低）：
 * <ol>
 *   <li>请求头 {@code X-API-Key: <key>}（Cursor mcp.json 的 headers 配置）</li>
 *   <li>请求头 {@code Authorization: Bearer <key>}</li>
 *   <li>查询参数 {@code ?api_key=<key>}（兜底给无法设置请求头的 SSE 客户端）</li>
 * </ol>
 *
 * <p>未配置 {@code app.mcp.api-key} 时 fail-closed：所有请求 401。
 * 鉴权通过后不在此处写 UserContext——SSE 传输下工具可能在其他线程执行，
 * userId 绑定由工具侧 {@code McpUserContexts.runAs} 完成。
 */
@Slf4j
@RequiredArgsConstructor
public class McpApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_API_KEY = "X-API-Key";
    private static final String BEARER_PREFIX = "Bearer ";

    private final McpServerProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isAuthorized(resolveApiKey(request))) {
            log.warn("MCP 请求鉴权失败: uri={}, ip={}", request.getRequestURI(), request.getRemoteAddr());
            writeUnauthorized(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String resolveApiKey(HttpServletRequest request) {
        String headerKey = request.getHeader(HEADER_API_KEY);
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey.trim();
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return request.getParameter("api_key");
    }

    private boolean isAuthorized(String provided) {
        String expected = properties.getApiKey();
        if (expected == null || expected.isBlank() || provided == null || provided.isBlank()) {
            return false;
        }
        // 常数时间比较，防时序侧信道
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
            Result.error(ErrorCode.UNAUTHORIZED, "无效的 MCP API Key")));
    }
}
