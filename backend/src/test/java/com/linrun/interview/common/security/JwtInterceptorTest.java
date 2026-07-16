package com.linrun.interview.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.exception.GlobalExceptionHandler;
import com.linrun.interview.common.result.Result;
import jakarta.servlet.DispatcherType;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@DisplayName("JWT 拦截器")
class JwtInterceptorTest {

  private final JwtUtil jwtUtil = mock(JwtUtil.class);
  private final JwtInterceptor interceptor = new JwtInterceptor(jwtUtil);
  private final Object handler = new Object();

  @AfterEach
  void tearDown() {
    UserContext.clear();
  }

  @Test
  @DisplayName("已认证请求在 ASYNC 再分发时从服务端请求属性恢复身份")
  void shouldRestoreAuthenticatedContextForAsyncRedispatch() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test/events");
    MockHttpServletResponse response = new MockHttpServletResponse();
    request.addHeader("Authorization", "Bearer access-token");
    when(jwtUtil.extractAccessUserId("access-token")).thenReturn(42L);
    when(jwtUtil.extractAccessRole("access-token")).thenReturn("USER");

    assertThat(interceptor.preHandle(request, response, handler)).isTrue();
    assertThat(UserContext.getUserId()).isEqualTo(42L);
    assertThat(UserContext.getRole()).isEqualTo("USER");

    interceptor.afterConcurrentHandlingStarted(request, response, handler);
    assertThat(UserContext.getUserId()).isNull();
    request.setDispatcherType(DispatcherType.ASYNC);
    request.removeHeader("Authorization");
    JwtUtil redispatchJwtUtil = mock(JwtUtil.class);
    JwtInterceptor redispatchInterceptor = new JwtInterceptor(redispatchJwtUtil);

    assertThat(redispatchInterceptor.preHandle(request, response, handler)).isTrue();
    assertThat(UserContext.getUserId()).isEqualTo(42L);
    assertThat(UserContext.getRole()).isEqualTo("USER");
    verifyNoInteractions(redispatchJwtUtil);

    redispatchInterceptor.afterCompletion(request, response, handler, null);
    assertThat(UserContext.getUserId()).isNull();
  }

  @Test
  @DisplayName("Spring MVC ASYNC 再分发不依赖第二次 Authorization 请求头")
  void shouldAuthenticateRealAsyncRedispatchOnlyOnce() throws Exception {
    when(jwtUtil.extractAccessUserId("access-token")).thenReturn(42L);
    when(jwtUtil.extractAccessRole("access-token")).thenReturn("USER");
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AsyncController())
        .addInterceptors(interceptor)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    MvcResult initial = mockMvc.perform(get("/api/test/async")
            .header("Authorization", "Bearer access-token"))
        .andExpect(request().asyncStarted())
        .andReturn();
    initial.getRequest().removeHeader("Authorization");

    mockMvc.perform(asyncDispatch(initial))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value("done"));
    verify(jwtUtil, times(1)).extractAccessUserId("access-token");
    verify(jwtUtil, times(1)).extractAccessRole("access-token");
  }

  @Test
  @DisplayName("未认证的初始请求仍被拒绝")
  void shouldRejectUnauthenticatedInitialRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test/events");

    assertThatThrownBy(() -> interceptor.preHandle(
        request, new MockHttpServletResponse(), handler))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
          assertThat(exception.getMessage()).isEqualTo("未登录或 token 无效");
        });
    assertThat(UserContext.getUserId()).isNull();
  }

  @Test
  @DisplayName("没有首次认证属性的 ASYNC 请求不能绕过 JWT 校验")
  void shouldRejectAsyncRequestWithoutAuthenticatedAttribute() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test/events");
    request.setDispatcherType(DispatcherType.ASYNC);

    assertThatThrownBy(() -> interceptor.preHandle(
        request, new MockHttpServletResponse(), handler))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED.getCode()));
    verifyNoInteractions(jwtUtil);
  }

  @RestController
  private static class AsyncController {

    @GetMapping(value = "/api/test/async", produces = MediaType.APPLICATION_JSON_VALUE)
    Callable<Result<String>> async() {
      return () -> Result.success("done");
    }
  }
}
