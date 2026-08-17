package com.linrun.interview.document.service;

import com.linrun.interview.document.service.impl.OfficialMineruClient;
import com.linrun.interview.document.constant.MineruTaskStatus;
import com.linrun.interview.document.vo.MineruTaskResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.document.config.MineruProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MinerU 官方 API 契约适配")
class OfficialMineruClientTest {

  private HttpServer server;
  private MineruProperties properties;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v4/extract/task", exchange -> {
      String body = exchange.getRequestMethod().equals("POST")
          ? "{\"code\":0,\"data\":{\"task_id\":\"task-1\"}}"
          : "{\"code\":0,\"data\":{\"state\":\"done\",\"full_zip_url\":\"http://127.0.0.1:"
              + server.getAddress().getPort() + "/result.zip\"}}";
      respond(exchange, 200, body, "application/json");
    });
    server.createContext("/api/v4/file-urls/batch", exchange -> respond(
        exchange, 200,
        "{\"code\":0,\"data\":{\"batch_id\":\"batch-1\",\"file_urls\":[\"http://127.0.0.1:"
            + server.getAddress().getPort() + "/upload\"]}}",
        "application/json"));
    server.createContext("/upload", exchange -> {
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.createContext("/api/v4/extract-results/batch/batch-1", exchange -> respond(
        exchange, 200,
        "{\"code\":0,\"data\":{\"batch_id\":\"batch-1\",\"extract_result\":[{"
            + "\"state\":\"done\",\"full_zip_url\":\"http://127.0.0.1:"
            + server.getAddress().getPort() + "/result.zip\","
            + "\"extract_progress\":{\"total_pages\":261}}]}}",
        "application/json"));
    server.createContext("/result.zip", exchange -> respond(
        exchange, 200, "PK-not-a-real-zip", "application/zip"));
    server.start();
    properties = new MineruProperties();
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setApiToken("test-token");
    properties.setAllowPrivateResultUrls(true);
    properties.setRequestTimeoutMs(3000);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  @DisplayName("提交、轮询使用官方 v4 路径与 Bearer Token")
  void submitsAndPolls() throws Exception {
    OfficialMineruClient client = new OfficialMineruClient(
        properties,
        new ObjectMapper(),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());

    String taskId = client.submit(URI.create("https://files.example.com/a.pdf"), "vlm");
    MineruTaskResult result = client.getTask(taskId);

    assertThat(taskId).isEqualTo("task-1");
    assertThat(result.status()).isEqualTo(MineruTaskStatus.SUCCEEDED);
    assertThat(result.resultZipUrl()).hasHost("127.0.0.1");
  }

  @Test
  @DisplayName("本地文件走官方 file-urls/batch 直传并按 batch_id 轮询")
  void uploadsLocalFileAndPollsBatch() throws Exception {
    OfficialMineruClient client = new OfficialMineruClient(
        properties,
        new ObjectMapper(),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());

    String batchId = client.submitLocalFile("pdf-bytes".getBytes(), "redis.pdf", "vlm", "1-200");
    MineruTaskResult result = client.getBatchResult(batchId);

    assertThat(batchId).isEqualTo("batch-1");
    assertThat(result.status()).isEqualTo(MineruTaskStatus.SUCCEEDED);
    assertThat(result.totalPages()).isEqualTo(261);
    assertThat(result.resultZipUrl()).hasHost("127.0.0.1");
  }

  @Test
  @DisplayName("Spring 容器明确选择生产构造器完成装配")
  void wiresProductionConstructorInSpringContext() {
    new ApplicationContextRunner()
        .withBean(MineruProperties.class, MineruProperties::new)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(OfficialMineruClient.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(OfficialMineruClient.class);
          assertThat(context.getBean(OfficialMineruClient.class)).isInstanceOf(MineruClient.class);
        });
  }

  private void respond(
      com.sun.net.httpserver.HttpExchange exchange,
      int status,
      String body,
      String contentType
  ) throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
