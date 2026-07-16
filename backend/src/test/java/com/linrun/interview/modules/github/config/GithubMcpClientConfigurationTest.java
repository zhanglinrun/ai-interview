package com.linrun.interview.modules.github.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.modules.github.mcp.GithubReadOnlyMcpClient;
import com.linrun.interview.modules.github.mcp.OfficialGithubRemoteMcpClient;
import com.linrun.interview.modules.github.mcp.UnavailableGithubMcpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("GitHub MCP 客户端条件装配")
class GithubMcpClientConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(GithubMcpClientConfiguration.class)
      .withBean(ObjectMapper.class, ObjectMapper::new);

  @Test
  @DisplayName("默认配置只装配零网络降级客户端")
  void shouldCreateFallbackByDefault() {
    contextRunner.run(context -> assertClient(context, UnavailableGithubMcpClient.class));
  }

  @Test
  @DisplayName("总开关关闭时 MCP 子开关不能启用官方客户端")
  void shouldCreateFallbackWhenGithubIsDisabledAndMcpIsEnabled() {
    contextRunner
        .withPropertyValues(
            "app.github.enabled=false",
            "app.github.mcp-enabled=true")
        .run(context -> assertClient(context, UnavailableGithubMcpClient.class));
  }

  @Test
  @DisplayName("总开关开启但 MCP 关闭时只装配零网络降级客户端")
  void shouldCreateFallbackWhenGithubIsEnabledAndMcpIsDisabled() {
    contextRunner
        .withPropertyValues(
            "app.github.enabled=true",
            "app.github.mcp-enabled=false")
        .run(context -> assertClient(context, UnavailableGithubMcpClient.class));
  }

  @Test
  @DisplayName("两个开关同时开启时只装配官方 MCP 客户端")
  void shouldCreateOfficialClientOnlyWhenBothSwitchesAreEnabled() {
    contextRunner
        .withPropertyValues(
            "app.github.enabled=true",
            "app.github.mcp-enabled=true")
        .run(context -> assertClient(context, OfficialGithubRemoteMcpClient.class));
  }

  private void assertClient(
      org.springframework.boot.test.context.assertj.AssertableApplicationContext context,
      Class<? extends GithubReadOnlyMcpClient> expectedType
  ) {
    assertThat(context).hasNotFailed();
    assertThat(context).hasSingleBean(GithubEvidenceProperties.class);
    assertThat(context).hasSingleBean(GithubReadOnlyMcpClient.class);
    assertThat(context.getBean(GithubReadOnlyMcpClient.class))
        .isExactlyInstanceOf(expectedType);
  }
}
