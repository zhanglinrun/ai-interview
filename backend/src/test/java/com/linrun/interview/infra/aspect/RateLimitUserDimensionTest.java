package com.linrun.interview.infra.aspect;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.auth.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N3 回归测试：USER 维度限流 key 必须按登录用户区分。
 *
 * <p>历史 bug：认证拦截器只写 UserContext，而切面只读
 * request attribute / header，导致登录用户限流 key 恒为 anonymous、全体共享一个令牌池。
 * 本测试通过反射调用 {@code generateKey} 验证 key 推导逻辑（真实 Redis 计数的
 * 完整链路见 {@link RateLimitIntegrationTest}）。
 */
@DisplayName("USER 维度限流 key 推导（N3 回归）")
class RateLimitUserDimensionTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    private String generateUserKey() throws Exception {
        RateLimitAspect aspect = new RateLimitAspect(null);
        Method method = RateLimitAspect.class.getDeclaredMethod(
            "generateKey", String.class, String.class, RateLimit.Dimension.class);
        method.setAccessible(true);
        return (String) method.invoke(aspect, "TestController", "query", RateLimit.Dimension.USER);
    }

    @Nested
    @DisplayName("UserContext 优先")
    class UserContextPriority {

        @Test
        @DisplayName("已登录用户：key 使用 UserContext 中的 userId")
        void keyUsesUserContextUserId() throws Exception {
            UserContext.setUserId(42L);

            assertThat(generateUserKey()).isEqualTo("ratelimit:{TestController:query}:user:42");
        }

        @Test
        @DisplayName("UserContext 优先于 X-User-Id header")
        void userContextTakesPrecedenceOverHeader() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-User-Id", "999");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            UserContext.setUserId(42L);

            assertThat(generateUserKey()).endsWith(":user:42");
        }
    }

    @Nested
    @DisplayName("多用户独立限流")
    class IndependentUsers {

        @Test
        @DisplayName("两个用户推导出不同的限流 key（独立令牌池）")
        void twoUsersGetIndependentKeys() throws Exception {
            UserContext.setUserId(1L);
            String keyUserA = generateUserKey();

            UserContext.setUserId(2L);
            String keyUserB = generateUserKey();

            assertThat(keyUserA).isNotEqualTo(keyUserB);
            assertThat(keyUserA).endsWith(":user:1");
            assertThat(keyUserB).endsWith(":user:2");
        }
    }

    @Nested
    @DisplayName("未登录兜底")
    class AnonymousFallback {

        @Test
        @DisplayName("无 UserContext 且无请求上下文：key 归为 anonymous")
        void fallsBackToAnonymous() throws Exception {
            assertThat(generateUserKey()).endsWith(":user:anonymous");
        }

        @Test
        @DisplayName("无 UserContext 时读取 X-User-Id header 兜底")
        void fallsBackToHeader() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-User-Id", "77");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            assertThat(generateUserKey()).endsWith(":user:77");
        }
    }
}
