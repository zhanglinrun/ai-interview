package com.linrun.interview.modules.github.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * GitHub 公共仓库证据流水线配置。
 *
 * <p>V1 固定连接 GitHub 官方公共 API，不接受用户提供 API 地址或 Token。平台 Token 可空，
 * 仅用于提高公共只读 API 的限流额度。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.github")
public class GithubEvidenceProperties {

  private boolean enabled = true;

  @NotBlank
  private String apiBaseUrl = "https://api.github.com";

  private String publicToken = "";

  @Min(1)
  @Max(60)
  private int timeoutSeconds = 10;

  /** 一次同步最多选择的文件数。 */
  @Min(1)
  @Max(1000)
  private int maxFiles = 120;

  /** 一次同步的源码正文总字节上限。 */
  @Min(1024)
  private long maxBytes = 10 * 1024 * 1024L;

  /** 单文件正文上限。 */
  @Min(1024)
  private long maxFileBytes = 256 * 1024L;

  /** Git tree 清单上限，防止超大仓库耗尽内存。 */
  @Min(100)
  @Max(100000)
  private int maxTreeEntries = 10000;

  /** 单次 bind/sync 的 GitHub HTTP 请求预算。 */
  @Min(3)
  @Max(2000)
  private int requestBudget = 150;

  /** 单个 API 响应体上限；tree 响应通常远小于此值。 */
  @Min(1024)
  @Max(67108864)
  private int maxApiResponseBytes = 16 * 1024 * 1024;

  @Min(20)
  @Max(400)
  private int chunkMaxLines = 120;

  @Min(0)
  @Max(100)
  private int chunkOverlapLines = 20;

  @Min(1000)
  private int chunkMaxChars = 12000;

  @Min(1)
  @Max(500)
  private int maxChunksPerFile = 80;

  @Min(1)
  @Max(10000)
  private int maxEvidenceChunks = 2000;

  private boolean mcpEnabled = false;

  /** GitHub 官方远程 MCP；只允许代码中固定的官方 HTTPS 主机。 */
  @NotBlank
  private String mcpEndpoint = "https://api.githubcopilot.com/mcp/";

  /** 平台只读 Token；为空时 MCP 安全降级到固定 SHA 快照。 */
  private String mcpAccessToken = "";

  @Min(1024)
  @Max(4194304)
  private int maxMcpResponseBytes = 1024 * 1024;
}
