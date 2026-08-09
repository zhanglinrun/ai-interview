package com.linrun.interview.github.client;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.github.config.GithubEvidenceProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** GitHub 官方 API 的 JDK HTTP 实现；明确禁用重定向并限制响应体。 */
@Component
public class JdkGithubHttpExecutor implements GithubHttpExecutor {

  private final GithubEvidenceProperties properties;
  private final URI apiBaseUri;
  private final HttpClient httpClient;

  public JdkGithubHttpExecutor(GithubEvidenceProperties properties) {
    this.properties = properties;
    this.apiBaseUri = validateOfficialApiBase(properties.getApiBaseUrl());
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Override
  public GithubHttpResponse get(String relativePath) {
    if (relativePath == null || !relativePath.startsWith("/")
        || relativePath.startsWith("//") || relativePath.contains("://")) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "GitHub API 相对路径非法");
    }
    URI target = apiBaseUri.resolve(relativePath);
    if (!target.getHost().equalsIgnoreCase(apiBaseUri.getHost())) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "GitHub API host 越界");
    }
    HttpRequest.Builder request = HttpRequest.newBuilder(target)
        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "ai-interview-github-evidence")
        .GET();
    if (properties.getPublicToken() != null && !properties.getPublicToken().isBlank()) {
      request.header("Authorization", "Bearer " + properties.getPublicToken().strip());
    }
    try {
      HttpResponse<InputStream> response = httpClient.send(
          request.build(), HttpResponse.BodyHandlers.ofInputStream());
      String body;
      try (InputStream input = response.body()) {
        byte[] bytes = input.readNBytes(properties.getMaxApiResponseBytes() + 1);
        if (bytes.length > properties.getMaxApiResponseBytes()) {
          throw new BusinessException(ErrorCode.GITHUB_SYNC_LIMIT_EXCEEDED,
              "GitHub API 响应超过安全上限，请缩小仓库范围");
        }
        body = new String(bytes, StandardCharsets.UTF_8);
      }
      return new GithubHttpResponse(response.statusCode(), response.headers().map(), body);
    } catch (BusinessException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub API 调用被中断", e);
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE,
          "GitHub API 暂时不可用，请稍后重试", e);
    }
  }

  static URI validateOfficialApiBase(String raw) {
    URI uri;
    try {
      uri = URI.create(raw == null ? "" : raw.strip());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "GitHub API base URL 配置非法", e);
    }
    String path = uri.getPath();
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || !"api.github.com".equals(uri.getHost().toLowerCase(Locale.ROOT))
        || uri.getPort() != -1
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || (path != null && !path.isBlank() && !"/".equals(path))) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR,
          "V1 GitHub API base URL 必须是 https://api.github.com");
    }
    return URI.create("https://api.github.com/");
  }
}
