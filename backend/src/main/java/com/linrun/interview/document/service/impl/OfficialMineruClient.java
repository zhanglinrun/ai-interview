package com.linrun.interview.document.service.impl;

import com.linrun.interview.document.service.MineruClient;
import com.linrun.interview.document.service.MineruClientException;
import com.linrun.interview.document.constant.MineruFailureCode;
import com.linrun.interview.document.constant.MineruTaskStatus;
import com.linrun.interview.document.vo.MineruTaskResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.document.config.MineruProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** MinerU {@code /api/v1/v4/extract/task} 官方异步 API 适配器。 */
@Component
public class OfficialMineruClient implements MineruClient {

  private final MineruProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  @Autowired
  public OfficialMineruClient(MineruProperties properties, ObjectMapper objectMapper) {
    this(
        properties,
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(properties.getConnectTimeoutMs(), 1)))
            // 禁止自动重定向，避免只校验初始 result URL 后被 30x 引向内网。
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  public OfficialMineruClient(
      MineruProperties properties,
      ObjectMapper objectMapper,
      HttpClient httpClient
  ) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
  }

  @Override
  public String submit(URI sourceUrl, String modelVersion) throws MineruClientException {
    requireConfigured();
    try {
      String body = objectMapper.writeValueAsString(Map.of(
          "url", sourceUrl.toASCIIString(),
          "model_version", modelVersion));
      HttpRequest request = authorizedRequest(resolve(properties.getSubmitPath()))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
          .build();
      JsonNode root = parseSuccessBody(sendJson(request));
      String taskId = firstText(root.path("data"), "task_id", "taskId");
      if (taskId == null || taskId.isBlank()) {
        throw new MineruClientException(
            MineruFailureCode.INVALID_RESPONSE, "MinerU 响应缺少 task_id");
      }
      return taskId;
    } catch (MineruClientException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MineruClientException(MineruFailureCode.INTERRUPTED, "MinerU 提交被中断", e);
    } catch (Exception e) {
      throw new MineruClientException(MineruFailureCode.UNKNOWN, "MinerU 提交失败", e);
    }
  }

  @Override
  public MineruTaskResult getTask(String providerTaskId) throws MineruClientException {
    requireConfigured();
    if (providerTaskId == null || providerTaskId.isBlank()) {
      throw new MineruClientException(
          MineruFailureCode.INVALID_RESPONSE, "providerTaskId 不能为空");
    }
    try {
      String encoded = URLEncoder.encode(providerTaskId, StandardCharsets.UTF_8)
          .replace("+", "%20");
      String path = properties.getStatusPath().replace("{taskId}", encoded);
      HttpRequest request = authorizedRequest(resolve(path)).GET().build();
      JsonNode root = parseSuccessBody(sendJson(request));
      JsonNode data = root.path("data");
      MineruTaskStatus status = parseStatus(firstText(data, "state", "status"));
      String failure = truncate(firstText(data, "err_msg", "error", "message"));
      String zipUrl = firstText(data, "full_zip_url", "zip_url", "download_url");
      URI resultUrl = zipUrl == null || zipUrl.isBlank() ? null : URI.create(zipUrl);
      if (status == MineruTaskStatus.SUCCEEDED && resultUrl == null) {
        throw new MineruClientException(
            MineruFailureCode.INVALID_RESPONSE, "MinerU 完成响应缺少结果 ZIP");
      }
      return new MineruTaskResult(status, resultUrl, failure);
    } catch (MineruClientException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MineruClientException(MineruFailureCode.INTERRUPTED, "MinerU 查询被中断", e);
    } catch (Exception e) {
      throw new MineruClientException(MineruFailureCode.INVALID_RESPONSE, "MinerU 状态查询失败", e);
    }
  }

  @Override
  public byte[] downloadResult(URI resultZipUrl) throws MineruClientException {
    validateResultUrl(resultZipUrl);
    try {
      HttpRequest request = HttpRequest.newBuilder(resultZipUrl)
          .timeout(requestTimeout())
          .GET()
          .build();
      HttpResponse<InputStream> response = httpClient.send(
          request, HttpResponse.BodyHandlers.ofInputStream());
      ensureHttpSuccess(response.statusCode());
      try (InputStream input = response.body()) {
        return readBounded(
            input, properties.getMaxZipBytes(), MineruFailureCode.RESULT_TOO_LARGE);
      }
    } catch (MineruClientException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MineruClientException(MineruFailureCode.INTERRUPTED, "结果下载被中断", e);
    } catch (Exception e) {
      throw new MineruClientException(
          MineruFailureCode.RESULT_DOWNLOAD_FAILED, "MinerU 结果下载失败", e);
    }
  }

  private HttpRequest.Builder authorizedRequest(URI uri) {
    return HttpRequest.newBuilder(uri)
        .timeout(requestTimeout())
        .header("Authorization", "Bearer " + properties.getApiToken())
        .header("Accept", "application/json");
  }

  private Duration requestTimeout() {
    return Duration.ofMillis(Math.max(properties.getRequestTimeoutMs(), 1));
  }

  private URI resolve(String path) {
    URI base = URI.create(properties.getBaseUrl());
    if (path.startsWith("/")) {
      return URI.create(base.getScheme() + "://" + base.getAuthority() + path);
    }
    String normalized = base.toString().endsWith("/") ? base.toString() : base + "/";
    return URI.create(normalized).resolve(path);
  }

  private JsonNode parseSuccessBody(String body) throws MineruClientException {
    try {
      JsonNode root = objectMapper.readTree(body);
      int providerCode = root.path("code").asInt(0);
      if (providerCode != 0) {
        throw new MineruClientException(
            MineruFailureCode.PROVIDER_REJECTED, "MinerU 拒绝请求，providerCode=" + providerCode);
      }
      return root;
    } catch (MineruClientException e) {
      throw e;
    } catch (Exception e) {
      throw new MineruClientException(
          MineruFailureCode.INVALID_RESPONSE, "MinerU 返回非预期 JSON", e);
    }
  }

  private String sendJson(HttpRequest request) throws Exception {
    HttpResponse<InputStream> response = httpClient.send(
        request, HttpResponse.BodyHandlers.ofInputStream());
    ensureHttpSuccess(response.statusCode());
    try (InputStream input = response.body()) {
      byte[] body = readBounded(
          input,
          properties.getMaxJsonResponseBytes(),
          MineruFailureCode.INVALID_RESPONSE);
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private void ensureHttpSuccess(int statusCode) throws MineruClientException {
    if (statusCode >= 200 && statusCode < 300) {
      return;
    }
    MineruFailureCode code = switch (statusCode) {
      case 401, 403 -> MineruFailureCode.AUTHENTICATION;
      case 429 -> MineruFailureCode.RATE_LIMITED;
      default -> statusCode >= 500
          ? MineruFailureCode.PROVIDER_5XX : MineruFailureCode.PROVIDER_REJECTED;
    };
    throw new MineruClientException(code, "MinerU HTTP 状态异常: " + statusCode);
  }

  private void requireConfigured() throws MineruClientException {
    if (properties.getApiToken() == null || properties.getApiToken().isBlank()) {
      throw new MineruClientException(
          MineruFailureCode.CONFIGURATION, "MinerU API Token 未配置");
    }
  }

  private MineruTaskStatus parseStatus(String raw) throws MineruClientException {
    String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "pending", "queued", "waiting" -> MineruTaskStatus.PENDING;
      case "running", "processing", "converting" -> MineruTaskStatus.RUNNING;
      case "done", "success", "succeeded", "completed" -> MineruTaskStatus.SUCCEEDED;
      case "failed", "error", "cancelled", "canceled" -> MineruTaskStatus.FAILED;
      default -> throw new MineruClientException(
          MineruFailureCode.INVALID_RESPONSE, "未知 MinerU 任务状态");
    };
  }

  private void validateResultUrl(URI uri) throws MineruClientException {
    if (uri == null || uri.getHost() == null
        || !("https".equalsIgnoreCase(uri.getScheme())
        || "http".equalsIgnoreCase(uri.getScheme()))) {
      throw new MineruClientException(
          MineruFailureCode.RESULT_URL_REJECTED, "MinerU 结果 URL 非法");
    }
    if (properties.isAllowPrivateResultUrls()) {
      return;
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
          throw new MineruClientException(
              MineruFailureCode.RESULT_URL_REJECTED, "MinerU 结果 URL 指向非公网地址");
        }
      }
    } catch (MineruClientException e) {
      throw e;
    } catch (Exception e) {
      throw new MineruClientException(
          MineruFailureCode.RESULT_URL_REJECTED, "无法校验 MinerU 结果 URL", e);
    }
  }

  private byte[] readBounded(
      InputStream input,
      long maxBytes,
      MineruFailureCode overflowCode
  ) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      total += read;
      if (total > maxBytes) {
        throw new MineruClientException(
            overflowCode, "MinerU HTTP 响应超过大小限制");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private String firstText(JsonNode node, String... fields) {
    for (String field : fields) {
      JsonNode value = node.path(field);
      if (value.isTextual() && !value.asText().isBlank()) {
        return value.asText();
      }
    }
    return null;
  }

  private String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= 300 ? value : value.substring(0, 300);
  }
}
