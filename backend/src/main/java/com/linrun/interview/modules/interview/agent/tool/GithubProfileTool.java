package com.linrun.interview.modules.interview.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * GitHub 候选人画像工具（LangChain4j @Tool 版，P3.2 MCP Client demo）。
 *
 * <p>面试官 Agent 的第三个 @Tool：读简历发现候选人 GitHub 用户名后调用它，拉取公开画像
 * （bio + 活跃仓库 + 语言分布），据此出「结合真实开源项目」的针对性问题——出题理由中即可
 * 引用真实 repo 信息，轨迹里能看到本工具调用。
 *
 * <p><b>选型叙事</b>：Server 侧用 Spring AI MCP starter 暴露业务能力，Client 侧本应用
 * 同生态的 langchain4j-mcp（{@code McpToolProvider} 把远端 MCP 工具挂进 AiServices）反向
 * 连 GitHub MCP server；考虑到公共 GitHub MCP server 稳定性，这里降级为官方公开 REST API
 * 直连（只读公开数据、无需鉴权），保留「MCP client 封装层」叙事——两套依赖并存与降级取舍
 * 本身是面试可讲的选型题。
 */
@Slf4j
@Component
public class GithubProfileTool {

  /** GitHub 用户名规则：字母数字与连字符，不以连字符开头/结尾，长度 ≤ 39（防 URL 注入/SSRF）。 */
  private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
  private static final int MAX_DESC_CHARS = 120;

  private final GithubToolProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public GithubProfileTool(GithubToolProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  @Tool("查询候选人 GitHub 公开画像（简介、活跃仓库、主用语言），用于结合其真实开源项目出题。"
      + "参数 username 是 GitHub 用户名，通常从候选人简历中获取。仅当已知候选人 GitHub 用户名时调用。")
  public String fetchGithubProfile(@P("GitHub 用户名，例如 torvalds") String username) {
    if (!properties.isEnabled()) {
      return "GitHub 画像工具未启用，请基于简历与知识库出题。";
    }
    if (username == null || username.isBlank()) {
      return "未提供 GitHub 用户名，无法查询画像。";
    }
    String normalized = username.strip();
    if (!USERNAME.matcher(normalized).matches()) {
      return "GitHub 用户名「" + normalized + "」格式非法，无法查询。";
    }

    try {
      JsonNode user = getJson("/users/" + normalized);
      if (user == null) {
        return "未找到 GitHub 用户「" + normalized + "」（可能不存在或接口限流），请基于简历出题。";
      }
      JsonNode repos = getJson("/users/" + normalized + "/repos?sort=pushed&direction=desc&per_page="
          + Math.max(1, properties.getMaxRepos()));
      return summarize(normalized, user, repos);
    } catch (Exception e) {
      log.warn("[GithubProfileTool] 查询失败: username={}, err={}", normalized, e.getMessage(), e);
      return "查询 GitHub 画像出错，请基于简历与知识库继续出题。";
    }
  }

  private JsonNode getJson(String path) throws Exception {
    URI uri = URI.create(properties.getBaseUrl() + path);
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "ai-interview-agent")
        .GET();
    if (properties.getToken() != null && !properties.getToken().isBlank()) {
      builder.header("Authorization", "Bearer " + properties.getToken().strip());
    }
    HttpResponse<String> response = httpClient.send(builder.build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() == 404) {
      return null;
    }
    if (response.statusCode() >= 400) {
      log.warn("[GithubProfileTool] GitHub API {} 返回 {}", path, response.statusCode());
      return null;
    }
    return objectMapper.readTree(response.body());
  }

  private String summarize(String username, JsonNode user, JsonNode repos) {
    StringBuilder sb = new StringBuilder();
    sb.append("GitHub 用户 @").append(username);
    String name = text(user, "name");
    if (!name.isBlank()) {
      sb.append("（").append(name).append("）");
    }
    sb.append('\n');
    String bio = text(user, "bio");
    if (!bio.isBlank()) {
      sb.append("简介：").append(bio).append('\n');
    }
    sb.append("公开仓库数：").append(user.path("public_repos").asInt(0))
        .append("，粉丝数：").append(user.path("followers").asInt(0)).append('\n');

    if (repos != null && repos.isArray() && !repos.isEmpty()) {
      List<String> lines = new ArrayList<>();
      for (JsonNode repo : repos) {
        if (repo.path("fork").asBoolean(false)) {
          continue; // 跳过 fork，只看原创项目
        }
        StringBuilder line = new StringBuilder("- ").append(text(repo, "name"));
        String lang = text(repo, "language");
        if (!lang.isBlank()) {
          line.append(" [").append(lang).append(']');
        }
        int stars = repo.path("stargazers_count").asInt(0);
        if (stars > 0) {
          line.append(" ★").append(stars);
        }
        String desc = text(repo, "description");
        if (!desc.isBlank()) {
          line.append("：").append(truncate(desc));
        }
        lines.add(line.toString());
        if (lines.size() >= Math.max(1, properties.getMaxRepos())) {
          break;
        }
      }
      if (!lines.isEmpty()) {
        sb.append("活跃原创仓库：\n").append(String.join("\n", lines)).append('\n');
      }
    }
    return sb.toString().strip();
  }

  private String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? "" : value.asText("").strip();
  }

  private String truncate(String text) {
    String normalized = text.replaceAll("\\s+", " ").trim();
    return normalized.length() <= MAX_DESC_CHARS
        ? normalized : normalized.substring(0, MAX_DESC_CHARS) + "...";
  }
}
