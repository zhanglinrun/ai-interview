package com.linrun.interview.modules.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.github.client.GithubHttpExecutor.GithubHttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GitHub REST API Client 契约")
class RestGithubPublicApiClientTest {

  @Test
  @DisplayName("解析公共仓库、固定 Commit、Tree 与校验后的 Blob")
  void shouldResolvePinnedSnapshot() {
    String commitSha = "a".repeat(40);
    String blobSha = "b".repeat(40);
    byte[] source = "class App {}".getBytes(StandardCharsets.UTF_8);
    QueueExecutor executor = new QueueExecutor(
        ok("""
            {"owner":{"login":"demo"},"name":"repo","html_url":"https://github.com/demo/repo",
             "default_branch":"main","size":12,"private":false}
            """),
        ok("{\"sha\":\"" + commitSha + "\"}"),
        ok("{\"truncated\":false,\"tree\":[{\"path\":\"src/App.java\","
            + "\"type\":\"blob\",\"sha\":\"" + blobSha + "\",\"size\":12}]}"),
        ok("{\"sha\":\"" + blobSha + "\",\"size\":" + source.length
            + ",\"encoding\":\"base64\",\"content\":\""
            + Base64.getEncoder().encodeToString(source) + "\"}"));
    RestGithubPublicApiClient client = new RestGithubPublicApiClient(executor, new ObjectMapper());

    assertThat(client.getPublicRepository("demo", "repo").defaultBranch()).isEqualTo("main");
    assertThat(client.resolveCommitSha("demo", "repo", "main")).isEqualTo(commitSha);
    assertThat(client.getTree("demo", "repo", commitSha).entries()).hasSize(1);
    assertThat(client.getBlob("demo", "repo", blobSha, 1024).bytes()).isEqualTo(source);
    assertThat(executor.paths).allMatch(path -> path.startsWith("/repos/demo/repo"));
  }

  @Test
  @DisplayName("429 和额度耗尽的 403 映射为可重试限流错误")
  void shouldMapRateLimit() {
    RestGithubPublicApiClient client429 = new RestGithubPublicApiClient(
        new QueueExecutor(response(429, Map.of(), "{}")), new ObjectMapper());
    RestGithubPublicApiClient client403 = new RestGithubPublicApiClient(
        new QueueExecutor(response(403, Map.of("X-RateLimit-Remaining", List.of("0")), "{}")),
        new ObjectMapper());

    assertRateLimited(client429);
    assertRateLimited(client403);
  }

  @Test
  @DisplayName("不跟随 GitHub API 重定向")
  void shouldRejectRedirect() {
    RestGithubPublicApiClient client = new RestGithubPublicApiClient(
        new QueueExecutor(response(301, Map.of("Location", List.of("http://127.0.0.1")), "")),
        new ObjectMapper());

    assertThatThrownBy(() -> client.getPublicRepository("demo", "repo"))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.GITHUB_INVALID_REPOSITORY_URL.getCode());
  }

  @Test
  @DisplayName("Blob 声明大小、SHA 或 Base64 不一致时拒绝正文")
  void shouldRejectTamperedBlob() {
    String sha = "b".repeat(40);
    RestGithubPublicApiClient client = new RestGithubPublicApiClient(
        new QueueExecutor(ok("{\"sha\":\"" + sha + "\",\"size\":99,"
            + "\"encoding\":\"base64\",\"content\":\"YQ==\"}")),
        new ObjectMapper());

    assertThatThrownBy(() -> client.getBlob("demo", "repo", sha, 1024))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("大小校验失败");
  }

  private void assertRateLimited(RestGithubPublicApiClient client) {
    assertThatThrownBy(() -> client.getPublicRepository("demo", "repo"))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.GITHUB_RATE_LIMITED.getCode());
  }

  private GithubHttpResponse ok(String body) {
    return response(200, Map.of(), body);
  }

  private GithubHttpResponse response(
      int status,
      Map<String, List<String>> headers,
      String body
  ) {
    return new GithubHttpResponse(status, headers, body);
  }

  private static final class QueueExecutor implements GithubHttpExecutor {
    private final Queue<GithubHttpResponse> responses = new ArrayDeque<>();
    private final List<String> paths = new java.util.ArrayList<>();

    private QueueExecutor(GithubHttpResponse... responses) {
      this.responses.addAll(List.of(responses));
    }

    @Override
    public GithubHttpResponse get(String relativePath) {
      paths.add(relativePath);
      return responses.remove();
    }
  }
}
