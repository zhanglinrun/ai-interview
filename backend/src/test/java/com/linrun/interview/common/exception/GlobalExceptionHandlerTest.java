package com.linrun.interview.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import cn.dev33.satoken.exception.NotLoginException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MultipartException;

@DisplayName("全局异常处理器")
class GlobalExceptionHandlerTest {

  private GlobalExceptionHandler handler;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    handler = new GlobalExceptionHandler();
    mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
        .setControllerAdvice(handler)
        .build();
  }

  @Test
  @DisplayName("SSE 客户端断连不再二次写入统一 JSON")
  void shouldNotWriteJsonAfterSseClientDisconnect() throws Exception {
    mockMvc.perform(get("/test/sse-disconnect")
            .accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isOk())
        .andExpect(content().bytes(new byte[0]))
        .andExpect(result -> {
          String contentType = result.getResponse().getContentType();
          assertThat(contentType == null
              || !contentType.startsWith(MediaType.APPLICATION_JSON_VALUE)).isTrue();
        });

    mockMvc.perform(get("/test/wrapped-disconnect")
            .accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isOk())
        .andExpect(content().bytes(new byte[0]));
  }

  @Test
  @DisplayName("非断连 IOException 仍返回原有统一系统错误")
  void shouldKeepUnifiedResponseForRealIOException() throws Exception {
    mockMvc.perform(get("/test/io-failure").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR.getCode()))
        .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"));
  }

  @Test
  @DisplayName("空查询参数返回参数错误而不是系统错误")
  void shouldTreatBlankRequiredParameterAsBadRequest() throws Exception {
    mockMvc.perform(get("/test/required-parameter?questionId=")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()))
        .andExpect(jsonPath("$.message").value("请求参数不完整或格式不正确"));
  }

  @Test
  @DisplayName("识别容器 ClientAbort 和 Windows 连接中止消息")
  void shouldRecognizeKnownDisconnectCausesOnly() {
    assertThat(GlobalExceptionHandler.isClientDisconnect(
        new IOException("outer", new ClientAbortException("connection reset by peer"))))
        .isTrue();
    assertThat(GlobalExceptionHandler.isClientDisconnect(
        new IOException("你的主机中的软件中止了一个已建立的连接")))
        .isTrue();
    assertThat(GlobalExceptionHandler.isClientDisconnect(new IOException("disk read failed")))
        .isFalse();
  }

  @Test
  @DisplayName("已提交或 SSE 响应的不可写异常不再返回 Result")
  void shouldSuppressNotWritableResponseForCommittedSse() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);

    assertThat(handler.handleHttpMessageNotWritableException(
        new HttpMessageNotWritableException("no converter"), response)).isNull();

    MockHttpServletResponse jsonResponse = new MockHttpServletResponse();
    assertThat(handler.handleHttpMessageNotWritableException(
        new HttpMessageNotWritableException("real serialization failure"), jsonResponse)
        .getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
  }

  @Test
  @DisplayName("未登录返回 401 而不是系统繁忙")
  void shouldMapNotLoginToUnauthorized() {
    NotLoginException expired = NotLoginException.newInstance("login", "-3", "token-1", null);
    var result = handler.handleNotLoginException(expired);
    assertThat(result.getCode()).isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
    assertThat(result.getMessage()).contains("token");
  }

  @Test
  @DisplayName("multipart 文件数超限返回明确错误而不是系统繁忙")
  void shouldMapPartCountExceededToBadRequest() {
    MultipartException exceeded = new MultipartException(
        "Failed to parse multipart",
        new IllegalStateException("The number of parts in this request exceeded the limit of 10"));

    assertThat(handler.handleMultipartException(exceeded).getCode())
        .isEqualTo(ErrorCode.BAD_REQUEST.getCode());
    assertThat(handler.handleMultipartException(exceeded).getMessage())
        .contains("文件过多");
    assertThat(GlobalExceptionHandler.isPartCountExceeded(exceeded)).isTrue();
    assertThat(GlobalExceptionHandler.isPartCountExceeded(
        new MultipartException("malformed boundary"))).isFalse();
  }

  @Test
  @DisplayName("ASYNC SSE 已结束时不再把业务异常写成 JSON")
  void shouldSuppressBusinessErrorOnlyForAsyncSseRedispatch() {
    BusinessException unauthorized = new BusinessException(
        ErrorCode.UNAUTHORIZED, "未登录或 token 无效");
    MockHttpServletRequest asyncRequest = new MockHttpServletRequest();
    asyncRequest.setDispatcherType(DispatcherType.ASYNC);
    MockHttpServletResponse sseResponse = new MockHttpServletResponse();
    sseResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);

    assertThat(handler.handleBusinessException(
        unauthorized, asyncRequest, sseResponse)).isNull();

    MockHttpServletRequest initialRequest = new MockHttpServletRequest();
    initialRequest.setDispatcherType(DispatcherType.REQUEST);
    assertThat(handler.handleBusinessException(
        unauthorized, initialRequest, new MockHttpServletResponse()).getCode())
        .isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
  }

  @RestController
  private static class ThrowingController {

    @GetMapping(value = "/test/sse-disconnect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    void disconnect() throws AsyncRequestNotUsableException {
      throw new AsyncRequestNotUsableException(
          "ServletOutputStream failed to write",
          new ClientAbortException("Software caused connection abort"));
    }

    @GetMapping(value = "/test/io-failure", produces = MediaType.APPLICATION_JSON_VALUE)
    void ioFailure() throws IOException {
      throw new IOException("disk read failed");
    }

    @GetMapping(value = "/test/wrapped-disconnect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    void wrappedDisconnect() {
      throw new IllegalStateException(
          "async dispatch failed", new ClientAbortException("broken pipe"));
    }

    @GetMapping(value = "/test/required-parameter", produces = MediaType.APPLICATION_JSON_VALUE)
    long requiredParameter(@RequestParam Long questionId) {
      return questionId;
    }
  }

  private static final class ClientAbortException extends IOException {
    private ClientAbortException(String message) {
      super(message);
    }
  }
}
