package com.linrun.interview.business.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.config.Judge0Properties;
import com.linrun.interview.business.constant.CodingLanguage;
import com.linrun.interview.business.constant.JudgeStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Judge0 有界客户端")
class Judge0JudgeClientTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("未配置时返回可补判状态且不访问网络")
  void shouldDegradeWhenNotConfigured() {
    Judge0JudgeClient client = new Judge0JudgeClient(properties(false, ""), new ObjectMapper());

    JudgeClientResult result = client.judge(request());

    assertThat(result.status()).isEqualTo(JudgeStatus.UNAVAILABLE);
    assertThat(result.failureCode()).isEqualTo("JUDGE_NOT_CONFIGURED");
  }

  @Test
  @DisplayName("提交后应轮询终态并解析通过数、耗时与内存")
  void shouldSubmitAndPollTerminalResult() throws IOException {
    AtomicInteger requests = new AtomicInteger();
    start(exchange -> {
      requests.incrementAndGet();
      if ("POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 201, "{\"token\":\"judge-token\"}");
      } else {
        respond(exchange, 200, """
            {"status":{"id":3,"description":"Accepted"},
             "stdout":"AIJUDGE_RESULT:3/3","time":"0.1234","memory":2048}
            """);
      }
    });
    Judge0JudgeClient client = new Judge0JudgeClient(
        properties(true, baseUrl()), new ObjectMapper());

    JudgeClientResult result = client.judge(request());

    assertThat(result.status()).isEqualTo(JudgeStatus.ACCEPTED);
    assertThat(result.providerSubmissionId()).isEqualTo("judge-token");
    assertThat(result.passedCount()).isEqualTo(3);
    assertThat(result.totalCount()).isEqualTo(3);
    assertThat(result.timeMs()).isEqualTo(123L);
    assertThat(result.memoryKb()).isEqualTo(2048L);
    assertThat(requests).hasValue(2);
  }

  @Test
  @DisplayName("超过上限的响应体必须中止读取并进入补判")
  void shouldRejectOversizedResponse() throws IOException {
    byte[] oversized = new byte[Judge0JudgeClient.MAX_RESPONSE_BYTES + 1];
    start(exchange -> respond(exchange, 200, oversized));
    Judge0JudgeClient client = new Judge0JudgeClient(
        properties(true, baseUrl()), new ObjectMapper());

    JudgeClientResult result = client.judge(request());

    assertThat(result.status()).isEqualTo(JudgeStatus.UNAVAILABLE);
    assertThat(result.failureCode()).isEqualTo("JUDGE_RESPONSE_TOO_LARGE");
  }

  @Test
  @DisplayName("无效 JSON 应映射为无效响应而非错误判题结论")
  void shouldMapMalformedJson() throws IOException {
    start(exchange -> respond(exchange, 200, "not-json"));
    Judge0JudgeClient client = new Judge0JudgeClient(
        properties(true, baseUrl()), new ObjectMapper());

    JudgeClientResult result = client.judge(request());

    assertThat(result.status()).isEqualTo(JudgeStatus.UNAVAILABLE);
    assertThat(result.failureCode()).isEqualTo("JUDGE_INVALID_RESPONSE");
  }

  @Test
  @DisplayName("鉴权、限流和上游故障应使用稳定错误码")
  void shouldMapHttpFailures() throws IOException {
    assertHttpFailure(401, "JUDGE_AUTH_FAILED");
    assertHttpFailure(429, "JUDGE_RATE_LIMITED");
    assertHttpFailure(503, "JUDGE_UPSTREAM_ERROR");
  }

  @Test
  @DisplayName("客户端不得自动跟随重定向")
  void shouldNotFollowRedirect() throws IOException {
    start(exchange -> {
      exchange.getResponseHeaders().add("Location", baseUrl() + "/redirected");
      respond(exchange, 302, "redirect");
    });
    Judge0JudgeClient client = new Judge0JudgeClient(
        properties(true, baseUrl()), new ObjectMapper());

    JudgeClientResult result = client.judge(request());

    assertThat(result.failureCode()).isEqualTo("JUDGE_HTTP_302");
  }

  @Test
  @DisplayName("编译错误只返回有界诊断信息")
  void shouldTruncateCompileDiagnostic() throws IOException {
    String diagnostic = "x".repeat(3000);
    start(exchange -> {
      if ("POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 200, "{\"token\":\"compile-token\"}");
      } else {
        respond(exchange, 200, "{\"status\":{\"id\":6,\"description\":\"Compilation Error\"},"
            + "\"compile_output\":\"" + diagnostic + "\"}");
      }
    });
    Judge0JudgeClient client = new Judge0JudgeClient(
        properties(true, baseUrl()), new ObjectMapper());

    JudgeClientResult result = client.judge(request());

    assertThat(result.status()).isEqualTo(JudgeStatus.COMPILE_ERROR);
    assertThat(result.failureCode()).isEqualTo("COMPILE_ERROR");
    assertThat(result.diagnostic()).hasSize(2000);
  }

  private void assertHttpFailure(int statusCode, String expectedCode) throws IOException {
    start(exchange -> respond(exchange, statusCode, "failure"));
    Judge0JudgeClient client = new Judge0JudgeClient(
        properties(true, baseUrl()), new ObjectMapper());

    JudgeClientResult result = client.judge(request());

    assertThat(result.failureCode()).isEqualTo(expectedCode);
    server.stop(0);
    server = null;
  }

  private void start(ExchangeHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/submissions", exchange -> {
      try {
        handler.handle(exchange);
      } finally {
        exchange.close();
      }
    });
    server.start();
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private Judge0Properties properties(boolean enabled, String baseUrl) {
    return new Judge0Properties(
        enabled, baseUrl, "", "", "", 62, 71,
        1000, 2000, 1, 2, 2.0, 65536);
  }

  private JudgeRequest request() {
    return new JudgeRequest(
        "submission-id", CodingLanguage.JAVA21, "class Main {}",
        "AIJUDGE_RESULT:3/3", 3);
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    respond(exchange, status, body.getBytes(StandardCharsets.UTF_8));
  }

  private void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }

  @FunctionalInterface
  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
