package com.linrun.interview.modules.mcp.support;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.security.UserContext;

import java.util.function.Supplier;

/**
 * MCP 工具的用户身份绑定。
 *
 * <p>业务 Service 层统一用 {@link UserContext}（ThreadLocal）做数据隔离，而 MCP 请求
 * 不经过 JwtInterceptor；且 SSE 传输下工具执行线程与鉴权过滤器所在线程未必相同，
 * 不能依赖过滤器写 ThreadLocal。因此每个工具方法自行以配置的 userId 包裹执行，
 * 结束后恢复原状，避免污染容器线程池中的复用线程。
 */
public final class McpUserContexts {

    private McpUserContexts() {
    }

    public static <T> T runAs(Long userId, Supplier<T> action) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                "app.mcp.user-id 未配置，无法确定 MCP 数据归属用户");
        }
        Long previous = UserContext.getUserId();
        UserContext.setUserId(userId);
        try {
            return action.get();
        } finally {
            if (previous != null) {
                UserContext.setUserId(previous);
            } else {
                UserContext.clear();
            }
        }
    }
}
