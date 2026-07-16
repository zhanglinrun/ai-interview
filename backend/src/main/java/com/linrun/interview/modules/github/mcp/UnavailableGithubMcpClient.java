package com.linrun.interview.modules.github.mcp;

import java.util.Optional;

/** 未配置官方 MCP 连接时的安全默认值，调用方自动使用固定 SHA 快照。 */
public class UnavailableGithubMcpClient implements GithubReadOnlyMcpClient {

  @Override
  public Optional<String> execute(GithubMcpRequest request) {
    return Optional.empty();
  }
}
