package com.linrun.interview.business.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.config.Judge0Properties;
import com.linrun.interview.business.constant.CodingLanguage;
import com.linrun.interview.business.constant.JudgeStatus;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Judge0 单次提交 + 有界轮询适配器。 */
public class Judge0JudgeClient implements JudgeClient {

  private static final Pattern RESULT_PATTERN = Pattern.compile("AIJUDGE_RESULT:(\\d+)/(\\d+)");
  private static final int MAX_DIAGNOSTIC_LENGTH = 2000;
  static final int MAX_RESPONSE_BYTES = 256 * 1024;

  private final Judge0Properties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public Judge0JudgeClient(Judge0Properties properties, ObjectMapper objectMapper) {
    this(properties, objectMapper, HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build());
  }

  Judge0JudgeClient(
      Judge0Properties properties,
      ObjectMapper objectMapper,
      HttpClient httpClient
  ) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
  }

  @Override
  public String providerName() {
    return "JUDGE0";
  }

  @Override
  public boolean available(CodingLanguage language) {
    return properties.availableFor(language);
  }

  @Override
  public JudgeClientResult judge(JudgeRequest request) {
    if (!available(request.language())) {
      return JudgeClientResult.unavailable(
          request.totalCount(), "JUDGE_NOT_CONFIGURED", "判题服务尚未配置，可稍后补判");
    }
    try {
      BoundedResponse submitted = sendSubmit(request);
      if (submitted.statusCode() == 429) {
        return JudgeClientResult.unavailable(
            request.totalCount(), "JUDGE_RATE_LIMITED", "判题服务繁忙，可稍后补判");
      }
      if (submitted.statusCode() < 200 || submitted.statusCode() >= 300) {
        return httpFailure(request.totalCount(), submitted.statusCode());
      }
      String token = objectMapper.readTree(submitted.body()).path("token").asText();
      if (token.isBlank()) {
        return JudgeClientResult.unavailable(
            request.totalCount(), "JUDGE_INVALID_RESPONSE", "判题服务返回无效，可稍后补判");
      }
      return poll(token, request.totalCount());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return JudgeClientResult.unavailable(
          request.totalCount(), "JUDGE_INTERRUPTED", "判题请求已中断，可稍后补判");
    } catch (ResponseTooLargeException e) {
      return JudgeClientResult.unavailable(
          request.totalCount(), "JUDGE_RESPONSE_TOO_LARGE", "判题服务返回过大，可稍后补判");
    } catch (JsonProcessingException e) {
      return JudgeClientResult.unavailable(
          request.totalCount(), "JUDGE_INVALID_RESPONSE", "判题服务返回无效，可稍后补判");
    } catch (IOException | IllegalArgumentException e) {
      return JudgeClientResult.unavailable(
          request.totalCount(), "JUDGE_UNAVAILABLE", "判题服务暂时不可用，可稍后补判");
    }
  }

  private BoundedResponse sendSubmit(JudgeRequest request)
      throws IOException, InterruptedException {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("source_code", request.sourceCode());
    payload.put("language_id", properties.languageId(request.language()));
    payload.put("expected_output", request.expectedOutput());
    payload.put("cpu_time_limit", properties.cpuTimeLimitSeconds());
    payload.put("memory_limit", properties.memoryLimitKb());
    HttpRequest httpRequest = requestBuilder(submissionUri())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
        .build();
    return send(httpRequest);
  }

  private JudgeClientResult poll(String token, int totalCount)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(properties.timeoutSeconds()).toNanos();
    while (System.nanoTime() < deadline) {
      HttpRequest request = requestBuilder(resultUri(token)).GET().build();
      BoundedResponse response = send(request);
      if (response.statusCode() == 429) {
        return JudgeClientResult.unavailable(
            totalCount, "JUDGE_RATE_LIMITED", "判题服务繁忙，可稍后补判");
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return httpFailure(totalCount, response.statusCode());
      }
      JsonNode root = objectMapper.readTree(response.body());
      int statusId = root.path("status").path("id").asInt(0);
      if (statusId == 1 || statusId == 2) {
        Thread.sleep(properties.pollIntervalMs());
        continue;
      }
      return mapTerminal(token, totalCount, root, statusId);
    }
    return JudgeClientResult.unavailable(
        totalCount, "JUDGE_TIMEOUT", "判题服务响应超时，可稍后补判");
  }

  private BoundedResponse send(HttpRequest request) throws IOException, InterruptedException {
    HttpResponse<InputStream> response = httpClient.send(
        request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream body = response.body()) {
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return new BoundedResponse(response.statusCode(), "");
      }
      long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
      if (declaredLength > MAX_RESPONSE_BYTES) {
        throw new ResponseTooLargeException();
      }
      byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
      if (bytes.length > MAX_RESPONSE_BYTES) {
        throw new ResponseTooLargeException();
      }
      return new BoundedResponse(
          response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
    }
  }

  private JudgeClientResult httpFailure(int totalCount, int statusCode) {
    if (statusCode == 401 || statusCode == 403) {
      return JudgeClientResult.unavailable(
          totalCount, "JUDGE_AUTH_FAILED", "判题服务鉴权失败，请检查服务配置");
    }
    if (statusCode == 404) {
      return JudgeClientResult.unavailable(
          totalCount, "JUDGE_ENDPOINT_NOT_FOUND", "判题服务端点不存在，请检查服务配置");
    }
    if (statusCode == 408 || statusCode == 504) {
      return JudgeClientResult.unavailable(
          totalCount, "JUDGE_TIMEOUT", "判题服务响应超时，可稍后补判");
    }
    if (statusCode >= 500) {
      return JudgeClientResult.unavailable(
          totalCount, "JUDGE_UPSTREAM_ERROR", "判题服务暂时不可用，可稍后补判");
    }
    return JudgeClientResult.unavailable(
        totalCount, "JUDGE_HTTP_" + statusCode, "判题服务暂时不可用，可稍后补判");
  }

  JudgeClientResult mapTerminal(String token, int totalCount, JsonNode root, int statusId) {
    String description = root.path("status").path("description").asText("");
    JudgeStatus status = mapStatus(statusId, description);
    int passed = parsePassed(root.path("stdout").asText(null), totalCount, status);
    String diagnostic = diagnostic(status, root);
    return new JudgeClientResult(
        token,
        status,
        passed,
        totalCount,
        diagnostic,
        parseTimeMs(root.path("time").asText(null)),
        root.path("memory").isNumber() ? root.path("memory").asLong() : null,
        failureCode(status));
  }

  private JudgeStatus mapStatus(int statusId, String description) {
    String normalized = description == null ? "" : description.toLowerCase();
    if (normalized.contains("memory")) {
      return JudgeStatus.MEMORY_LIMIT_EXCEEDED;
    }
    return switch (statusId) {
      case 3 -> JudgeStatus.ACCEPTED;
      case 4 -> JudgeStatus.WRONG_ANSWER;
      case 5 -> JudgeStatus.TIME_LIMIT_EXCEEDED;
      case 6 -> JudgeStatus.COMPILE_ERROR;
      case 7, 8, 9, 10, 11, 12, 14 -> JudgeStatus.RUNTIME_ERROR;
      default -> JudgeStatus.INTERNAL_ERROR;
    };
  }

  private int parsePassed(String stdout, int totalCount, JudgeStatus status) {
    if (stdout != null) {
      Matcher matcher = RESULT_PATTERN.matcher(stdout);
      if (matcher.find()) {
        return Math.min(Integer.parseInt(matcher.group(1)), totalCount);
      }
    }
    return status == JudgeStatus.ACCEPTED ? totalCount : 0;
  }

  private String diagnostic(JudgeStatus status, JsonNode root) {
    String value = switch (status) {
      case ACCEPTED -> null;
      case WRONG_ANSWER -> "隐藏用例未全部通过";
      case TIME_LIMIT_EXCEEDED -> "执行超时";
      case MEMORY_LIMIT_EXCEEDED -> "内存超限";
      case COMPILE_ERROR -> firstText(root, "compile_output", "message");
      case RUNTIME_ERROR -> firstText(root, "stderr", "message");
      case INTERNAL_ERROR -> "判题服务内部错误，可稍后补判";
      default -> "判题尚未完成";
    };
    return truncate(value);
  }

  private String firstText(JsonNode root, String first, String second) {
    String value = root.path(first).asText("");
    return value.isBlank() ? root.path(second).asText("") : value;
  }

  private String truncate(String value) {
    if (value == null || value.length() <= MAX_DIAGNOSTIC_LENGTH) {
      return value;
    }
    return value.substring(0, MAX_DIAGNOSTIC_LENGTH);
  }

  private String failureCode(JudgeStatus status) {
    return switch (status) {
      case ACCEPTED -> null;
      case WRONG_ANSWER -> "WRONG_ANSWER";
      case COMPILE_ERROR -> "COMPILE_ERROR";
      case RUNTIME_ERROR -> "RUNTIME_ERROR";
      case TIME_LIMIT_EXCEEDED -> "TIME_LIMIT_EXCEEDED";
      case MEMORY_LIMIT_EXCEEDED -> "MEMORY_LIMIT_EXCEEDED";
      default -> "JUDGE_INTERNAL_ERROR";
    };
  }

  private Long parseTimeMs(String seconds) {
    if (seconds == null || seconds.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(seconds).multiply(BigDecimal.valueOf(1000))
          .setScale(0, RoundingMode.HALF_UP).longValue();
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private HttpRequest.Builder requestBuilder(URI uri) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofMillis(properties.requestTimeoutMs()))
        .header("Accept", "application/json");
    if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
      builder.header(properties.apiKeyHeader(), properties.apiKey());
    }
    if (properties.apiHost() != null && !properties.apiHost().isBlank()) {
      builder.header("X-RapidAPI-Host", properties.apiHost());
    }
    return builder;
  }

  private URI submissionUri() {
    return URI.create(trimTrailingSlash(properties.baseUrl())
        + "/submissions?base64_encoded=false&wait=false");
  }

  private URI resultUri(String token) {
    return URI.create(trimTrailingSlash(properties.baseUrl()) + "/submissions/" + token
        + "?base64_encoded=false&fields=token,status,stdout,stderr,compile_output,time,memory,message");
  }

  private String trimTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private record BoundedResponse(int statusCode, String body) {
  }

  private static final class ResponseTooLargeException extends IOException {
  }
}
