package com.linrun.interview.modules.interview.agent.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GitHub 候选人画像工具配置（P3.2 MCP Client demo）。
 *
 * <p>面试官 Agent 通过 {@code fetch_github_profile} 工具反向集成外部生态：优先走
 * MCP client 语义（与主框架同生态的 langchain4j-mcp 挂 GitHub MCP server），
 * 稳定性不足时降级为 GitHub 公开 REST API——本工具即降级实现，封装层叙事不变。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.agent.github")
public class GithubToolProperties {

  /** 是否启用 GitHub 画像工具（关闭时工具返回禁用提示，不参与 function-calling 决策成本） */
  private boolean enabled = true;

  /** GitHub API 基址（自建 GitHub Enterprise 时可改） */
  private String baseUrl = "https://api.github.com";

  /** 可选 Personal Access Token（仅提升匿名 60 次/时的限流额度，读公开数据无需鉴权） */
  private String token = "";

  /** 单次 HTTP 请求超时（秒） */
  private int timeoutSeconds = 6;

  /** 摘要中回捞的仓库数上限 */
  private int maxRepos = 5;
}
