package com.linrun.interview.common.security;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * JWT 拦截器：从请求头提取 token，验证并注入 userId 到 UserContext。
 * 未登录（无 token 或无效 token）抛 401。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements AsyncHandlerInterceptor {

    private static final String AUTHENTICATED_USER_ID_ATTRIBUTE =
        JwtInterceptor.class.getName() + ".authenticatedUserId";
    private static final String AUTHENTICATED_ROLE_ATTRIBUTE =
        JwtInterceptor.class.getName() + ".authenticatedRole";

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // OPTIONS 请求直接放行（CORS 预检）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // Servlet 异步请求完成或报错时会再次 ASYNC dispatch，且该次分发不保证仍携带
        // Authorization 请求头。只恢复同一个请求在首次认证后写入的服务端属性；没有该属性的
        // ASYNC 请求仍走完整 JWT 校验，不能借 dispatcherType 绕过认证。
        UserContext.clear();
        if (request.getDispatcherType() == DispatcherType.ASYNC
            && restoreAuthenticatedContext(request)) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或 token 无效");
        }

        String token = authHeader.substring(7);
        Long userId = jwtUtil.extractAccessUserId(token);
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或 token 无效");
        }

        UserContext.setUserId(userId);
        String role = jwtUtil.extractAccessRole(token);
        UserContext.setRole(role);
        request.setAttribute(AUTHENTICATED_USER_ID_ATTRIBUTE, userId);
        if (role != null) {
            request.setAttribute(AUTHENTICATED_ROLE_ATTRIBUTE, role);
        }
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        // 原始 Servlet 线程即将归还线程池。身份保留在请求属性中，ThreadLocal 必须及时清理。
        UserContext.clear();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private boolean restoreAuthenticatedContext(HttpServletRequest request) {
        Object userId = request.getAttribute(AUTHENTICATED_USER_ID_ATTRIBUTE);
        if (!(userId instanceof Long authenticatedUserId) || authenticatedUserId <= 0) {
            return false;
        }
        UserContext.setUserId(authenticatedUserId);
        Object role = request.getAttribute(AUTHENTICATED_ROLE_ATTRIBUTE);
        if (role instanceof String authenticatedRole) {
            UserContext.setRole(authenticatedRole);
        }
        return true;
    }
}
