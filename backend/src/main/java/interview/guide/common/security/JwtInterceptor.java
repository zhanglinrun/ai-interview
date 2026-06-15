package interview.guide.common.security;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 拦截器：从请求头提取 token，验证并注入 userId 到 UserContext。
 * 未登录（无 token 或无效 token）抛 401。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // OPTIONS 请求直接放行（CORS 预检）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
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
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
