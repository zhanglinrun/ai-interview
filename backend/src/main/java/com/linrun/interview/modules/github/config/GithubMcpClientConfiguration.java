package com.linrun.interview.modules.github.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.modules.github.mcp.OfficialGithubRemoteMcpClient;
import com.linrun.interview.modules.github.mcp.UnavailableGithubMcpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 根据 GitHub 总开关与 MCP 子开关互斥装配远程客户端或零网络降级客户端。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GithubEvidenceProperties.class)
public class GithubMcpClientConfiguration {

  /** GitHub 总开关开启时，再由 MCP 子开关选择具体实现。 */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      prefix = "app.github",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class GithubEnabledConfiguration {

    @Bean
    @ConditionalOnProperty(
        prefix = "app.github",
        name = "mcp-enabled",
        havingValue = "true")
    OfficialGithubRemoteMcpClient officialGithubRemoteMcpClient(
        GithubEvidenceProperties properties,
        ObjectMapper objectMapper
    ) {
      return new OfficialGithubRemoteMcpClient(properties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "app.github",
        name = "mcp-enabled",
        havingValue = "false",
        matchIfMissing = true)
    UnavailableGithubMcpClient mcpDisabledGithubMcpClient() {
      return new UnavailableGithubMcpClient();
    }
  }

  /** 总开关关闭时始终使用本地快照降级，不允许 MCP 子开关越过总开关。 */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      prefix = "app.github",
      name = "enabled",
      havingValue = "false")
  static class GithubDisabledConfiguration {

    @Bean
    UnavailableGithubMcpClient githubDisabledMcpClient() {
      return new UnavailableGithubMcpClient();
    }
  }
}
