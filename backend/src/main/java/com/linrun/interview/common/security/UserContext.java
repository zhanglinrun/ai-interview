package com.linrun.interview.common.security;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;

/**
 * 用户上下文：ThreadLocal 存储当前请求的 userId。
 * 由 JwtInterceptor 在请求开始时注入，请求结束时清理。
 */
public class UserContext {

    private static final String ROLE_ADMIN = "ADMIN";

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE_HOLDER = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    public static Long getUserId() {
        return USER_ID_HOLDER.get();
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
        return ROLE_HOLDER.get();
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
