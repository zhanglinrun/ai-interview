package com.linrun.interview.modules.github.mcp;

/** 官方 GitHub MCP 的最小只读请求契约。 */
public record GithubMcpRequest(
    String operation,
    String owner,
    String repository,
    String commitSha,
    String path
) {
}
