package com.linrun.interview.auth.security;

import cn.dev33.satoken.stp.StpUtil;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;

/**
 * 当前用户上下文。
 *
 * <p>生产请求优先从 Sa-Token 读取，ThreadLocal 仅作为单元测试和离线任务的
 * 显式 fallback。领域代码不需要知道 token 的具体格式。</p>
 */
public class UserContext {

    private static final String ROLE_ADMIN = "ADMIN";

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE_HOLDER = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    public static Long getUserId() {
        Long testUserId = USER_ID_HOLDER.get();
        if (testUserId != null) {
            return testUserId;
        }
        try {
            if (StpUtil.isLogin()) {
                return StpUtil.getLoginIdAsLong();
            }
        } catch (RuntimeException ignored) {
            // 非 Web 测试或尚未初始化 Sa-Token 时使用空上下文。
        }
        return null;
    }

    public static Long requireUserId() {
        Long userId = getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或 token 无效");
        }
        return userId;
    }

    public static void setRole(String role) {
        ROLE_HOLDER.set(role);
    }

    public static String getRole() {
        String role = ROLE_HOLDER.get();
        if (role != null) {
            return role;
        }
        try {
            if (StpUtil.isLogin() && StpUtil.hasRole("admin")) {
                return ROLE_ADMIN;
            }
        } catch (RuntimeException ignored) {
            // 与 getUserId 保持一致，离线场景不抛出 token 基础设施异常。
        }
        return null;
    }

    public static boolean isAdmin() {
        return ROLE_ADMIN.equalsIgnoreCase(getRole());
    }

    /** 要求当前用户为管理员，否则抛 403（用于 LLM Provider 等全局配置类接口）。 */
    public static void requireAdmin() {
        requireUserId();
        if (!isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
    }

    public static void clear() {
        USER_ID_HOLDER.remove();
        ROLE_HOLDER.remove();
    }
}
