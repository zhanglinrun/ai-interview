package com.linrun.interview.github.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.github.config.GithubEvidenceProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GitHub 官方远程 MCP Streamable HTTP 客户端。
 *
 * <p>客户端只实现固定 SHA 文件读取；端点固定为 GitHub 官方主机，禁止重定向，响应有界。
 * 未配置 Token 时返回 empty，由调用方回退本地快照。
 */
public class OfficialGithubRemoteMcpClient implements GithubReadOnlyMcpClient {

  private static final String OFFICIAL_HOST = "api.githubcopilot.com";
  private static final String PROTOCOL_VERSION = "2025-03-26";
  private static final String TOOL_GET_FILE = "get_file_contents";

  private final GithubEvidenceProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final URI endpoint;
  private final AtomicLong requestIds = new AtomicLong(1L);

  public OfficialGithubRemoteMcpClient(
      GithubEvidenceProperties properties,
      ObjectMapper objectMapper
  ) {
    this(properties, objectMapper, HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build());
  }

  OfficialGithubRemoteMcpClient(
      GithubEvidenceProperties properties,
      ObjectMapper objectMapper,
      HttpClient httpClient
  ) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.endpoint = validateOfficialEndpoint(properties.getMcpEndpoint());
  }

  @Override
  public Optional<String> execute(GithubMcpRequest request) {
    if (properties.getMcpAccessToken() == null
        || properties.getMcpAccessToken().isBlank()) {
      return Optional.empty();
    }
    if (request == null || !TOOL_GET_FILE.equals(request.operation())) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "GitHub MCP 客户端仅允许读取文件");
    }
    try {
      RpcResponse initialized = send(initializeRequest(), null);
      String sessionId = initialized.sessionId();
      send(initializedNotification(), sessionId);
      RpcResponse toolResponse = send(toolRequest(request), sessionId);
      return extractFileContent(parseRpcPayload(toolResponse.body()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub MCP 请求被中断", e);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(
          ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub MCP 暂时不可用", e);
    }
  }

  private RpcResponse send(ObjectNode payload, String sessionId)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
        .header("Authorization", "Bearer " + properties.getMcpAccessToken().strip())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .header("X-MCP-Toolsets", "repos")
        .POST(HttpRequest.BodyPublishers.ofString(
            objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
    if (sessionId != null && !sessionId.isBlank()) {
      builder.header("Mcp-Session-Id", sessionId);
    }
    HttpResponse<InputStream> response = httpClient.send(
        builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    byte[] bytes;
    try (InputStream body = response.body()) {
      bytes = readLimited(body, properties.getMaxMcpResponseBytes());
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new BusinessException(
          ErrorCode.GITHUB_API_UNAVAILABLE,
          "GitHub MCP HTTP " + response.statusCode());
    }
    return new RpcResponse(
        new String(bytes, StandardCharsets.UTF_8),
        response.headers().firstValue("Mcp-Session-Id").orElse(sessionId));
  }

  private ObjectNode initializeRequest() {
    ObjectNode params = objectMapper.createObjectNode();
    params.put("protocolVersion", PROTOCOL_VERSION);
    params.set("capabilities", objectMapper.createObjectNode());
    ObjectNode client = objectMapper.createObjectNode();
    client.put("name", "ai-interview");
    client.put("version", "1.0.0");
    params.set("clientInfo", client);
    return rpc(requestIds.getAndIncrement(), "initialize", params);
  }

  private ObjectNode initializedNotification() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("jsonrpc", "2.0");
    node.put("method", "notifications/initialized");
    node.set("params", objectMapper.createObjectNode());
    return node;
  }

  private ObjectNode toolRequest(GithubMcpRequest request) {
    ObjectNode arguments = objectMapper.createObjectNode();
    arguments.put("owner", request.owner());
    arguments.put("repo", request.repository());
    arguments.put("path", request.path());
    arguments.put("ref", request.commitSha());
    ObjectNode params = objectMapper.createObjectNode();
    params.put("name", TOOL_GET_FILE);
    params.set("arguments", arguments);
    return rpc(requestIds.getAndIncrement(), "tools/call", params);
  }

  private ObjectNode rpc(long id, String method, JsonNode params) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("jsonrpc", "2.0");
    node.put("id", id);
    node.put("method", method);
    node.set("params", params);
    return node;
  }

  JsonNode parseRpcPayload(String body) throws IOException {
    if (body == null || body.isBlank()) {
      return objectMapper.createObjectNode();
    }
    String trimmed = body.strip();
    if (trimmed.startsWith("{")) {
      return objectMapper.readTree(trimmed);
    }
    String data = trimmed.lines()
        .map(String::strip)
        .filter(line -> line.startsWith("data:"))
        .map(line -> line.substring("data:".length()).strip())
        .filter(line -> !line.isBlank() && !"[DONE]".equals(line))
        .reduce((first, second) -> second)
        .orElseThrow(() -> new IOException("MCP SSE 响应没有 data JSON"));
    return objectMapper.readTree(data);
  }

  Optional<String> extractFileContent(JsonNode root) throws IOException {
    if (root.hasNonNull("error")) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub MCP 返回 RPC 错误");
    }
    JsonNode result = root.path("result");
    if (result.path("isError").asBoolean(false)) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub MCP 工具执行失败");
    }
    JsonNode content = result.path("content");
    if (!content.isArray()) {
      return Optional.empty();
    }
    for (JsonNode item : content) {
      if (!"text".equals(item.path("type").asText()) || !item.path("text").isTextual()) {
        continue;
      }
      String normalized = unwrapFilePayload(item.path("text").asText());
      if (normalized != null) {
        long max = properties.getMaxFileBytes();
        if (normalized.getBytes(StandardCharsets.UTF_8).length > max) {
          throw new BusinessException(
              ErrorCode.GITHUB_SYNC_LIMIT_EXCEEDED, "GitHub MCP 文件超过单文件安全上限");
        }
        return Optional.of(normalized);
      }
    }
    return Optional.empty();
  }

  private String unwrapFilePayload(String text) {
    if (text == null) {
      return null;
    }
    String trimmed = text.strip();
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
      return text;
    }
    try {
      JsonNode node = objectMapper.readTree(trimmed);
      JsonNode payload = node.has("content") ? node : node.path("data");
      JsonNode value = payload.path("content");
      if (!value.isTextual()) {
        return text;
      }
      String content = value.asText();
      if ("base64".equals(payload.path("encoding").asText().toLowerCase(Locale.ROOT))) {
        return new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
      }
      return content;
    } catch (Exception e) {
      return text;
    }
  }

  private byte[] readLimited(InputStream input, int maxBytes) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
    byte[] buffer = new byte[8192];
    int total = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      total += read;
      if (total > maxBytes) {
        throw new BusinessException(
            ErrorCode.GITHUB_SYNC_LIMIT_EXCEEDED, "GitHub MCP 响应超过安全上限");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private URI validateOfficialEndpoint(String value) {
    try {
      URI uri = URI.create(value);
      String path = uri.getPath() == null ? "" : uri.getPath();
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || !OFFICIAL_HOST.equalsIgnoreCase(uri.getHost())
          || !("/mcp".equals(path) || "/mcp/".equals(path))
          || uri.getUserInfo() != null || uri.getPort() != -1) {
        throw new IllegalArgumentException("只允许 GitHub 官方远程 MCP HTTPS 端点");
      }
      return uri;
    } catch (Exception e) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "GitHub MCP Endpoint 非法", e);
    }
  }

  private record RpcResponse(String body, String sessionId) {
  }
}
