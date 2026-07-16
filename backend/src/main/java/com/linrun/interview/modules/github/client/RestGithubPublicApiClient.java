package com.linrun.interview.modules.github.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.github.client.GithubHttpExecutor.GithubHttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** GitHub 官方 REST API v3 只读适配器。 */
@Component
public class RestGithubPublicApiClient implements GithubPublicApiClient {

  private static final Pattern COORDINATE = Pattern.compile("[A-Za-z0-9._-]{1,100}");
  private static final Pattern REF = Pattern.compile("[A-Za-z0-9._/-]{1,200}");
  private static final Pattern SHA = Pattern.compile("[a-fA-F0-9]{40}");

  private final GithubHttpExecutor httpExecutor;
  private final ObjectMapper objectMapper;

  public RestGithubPublicApiClient(GithubHttpExecutor httpExecutor, ObjectMapper objectMapper) {
    this.httpExecutor = httpExecutor;
    this.objectMapper = objectMapper;
  }

  @Override
  public RepositoryDescriptor getPublicRepository(String owner, String repository) {
    validateCoordinate(owner, "owner");
    validateCoordinate(repository, "repository");
    JsonNode json = readJson("/repos/" + owner + "/" + repository);
    return new RepositoryDescriptor(
        json.path("owner").path("login").asText(owner),
        json.path("name").asText(repository),
        json.path("html_url").asText("https://github.com/" + owner + "/" + repository),
        requiredText(json, "default_branch"),
        Math.max(0L, json.path("size").asLong(0L)),
        json.path("private").asBoolean(false));
  }

  @Override
  public String resolveCommitSha(String owner, String repository, String ref) {
    validateCoordinate(owner, "owner");
    validateCoordinate(repository, "repository");
    if (ref == null || !REF.matcher(ref).matches() || ref.contains("..") || ref.startsWith("/")) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "GitHub ref 格式非法");
    }
    JsonNode json = readJson("/repos/" + owner + "/" + repository + "/commits/" + ref);
    String sha = requiredText(json, "sha");
    if (!SHA.matcher(sha).matches()) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub 返回了非法 Commit SHA");
    }
    return sha.toLowerCase();
  }

  @Override
  public RepositoryTree getTree(String owner, String repository, String commitSha) {
    validateCoordinate(owner, "owner");
    validateCoordinate(repository, "repository");
    validateSha(commitSha);
    JsonNode json = readJson("/repos/" + owner + "/" + repository
        + "/git/trees/" + commitSha + "?recursive=1");
    List<TreeEntry> entries = new ArrayList<>();
    JsonNode tree = json.path("tree");
    if (tree.isArray()) {
      for (JsonNode entry : tree) {
        entries.add(new TreeEntry(
            entry.path("path").asText(""),
            entry.path("type").asText(""),
            entry.path("sha").asText(""),
            Math.max(0L, entry.path("size").asLong(0L))));
      }
    }
    return new RepositoryTree(entries, json.path("truncated").asBoolean(false));
  }

  @Override
  public BlobContent getBlob(
      String owner,
      String repository,
      String blobSha,
      long maxDecodedBytes
  ) {
    validateCoordinate(owner, "owner");
    validateCoordinate(repository, "repository");
    validateSha(blobSha);
    if (maxDecodedBytes <= 0 || maxDecodedBytes > Integer.MAX_VALUE) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "GitHub 单文件大小上限非法");
    }
    JsonNode json = readJson("/repos/" + owner + "/" + repository + "/git/blobs/" + blobSha);
    String returnedSha = requiredText(json, "sha");
    if (!returnedSha.equalsIgnoreCase(blobSha)) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub Blob SHA 校验失败");
    }
    long declaredSize = json.path("size").asLong(-1L);
    if (declaredSize < 0 || declaredSize > maxDecodedBytes) {
      throw new BusinessException(ErrorCode.GITHUB_SYNC_LIMIT_EXCEEDED,
          "GitHub 文件超过单文件同步上限");
    }
    if (!"base64".equalsIgnoreCase(json.path("encoding").asText(""))) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub Blob 编码不受支持");
    }
    byte[] bytes;
    try {
      String compact = json.path("content").asText("").replaceAll("\\s+", "");
      bytes = Base64.getDecoder().decode(compact.getBytes(StandardCharsets.US_ASCII));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub Blob Base64 非法", e);
    }
    if (bytes.length != declaredSize || bytes.length > maxDecodedBytes) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub Blob 大小校验失败");
    }
    return new BlobContent(returnedSha.toLowerCase(), bytes);
  }

  private JsonNode readJson(String path) {
    GithubHttpResponse response = httpExecutor.get(path);
    int status = response.statusCode();
    if (status == 404) {
      throw new BusinessException(ErrorCode.GITHUB_REPOSITORY_NOT_FOUND,
          "GitHub 仓库、Commit 或文件不存在");
    }
    if (status == 429 || (status == 403
        && "0".equals(response.firstHeader("X-RateLimit-Remaining")))) {
      throw new BusinessException(ErrorCode.GITHUB_RATE_LIMITED,
          "GitHub API 已限流，请稍后重试或配置平台只读 Token");
    }
    if (status >= 300 && status < 400) {
      throw new BusinessException(ErrorCode.GITHUB_INVALID_REPOSITORY_URL,
          "GitHub API 返回重定向，已拒绝跟随；请使用仓库规范 URL");
    }
    if (status == 401 || status == 403) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE,
          "GitHub API 拒绝访问，请检查平台 Token");
    }
    if (status < 200 || status >= 300) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE,
          "GitHub API 暂时不可用（HTTP " + status + "）");
    }
    try {
      return objectMapper.readTree(response.body());
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE, "GitHub API 响应解析失败", e);
    }
  }

  private String requiredText(JsonNode node, String field) {
    String value = node.path(field).asText("").strip();
    if (value.isEmpty()) {
      throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE,
          "GitHub API 响应缺少字段: " + field);
    }
    return value;
  }

  private void validateCoordinate(String value, String field) {
    if (value == null || !COORDINATE.matcher(value).matches()
        || value.equals(".") || value.equals("..")) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "GitHub " + field + " 非法");
    }
  }

  private void validateSha(String sha) {
    if (sha == null || !SHA.matcher(sha).matches()) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "GitHub SHA 非法");
    }
  }
}
