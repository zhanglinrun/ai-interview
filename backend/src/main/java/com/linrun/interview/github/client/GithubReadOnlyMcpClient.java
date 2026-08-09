package com.linrun.interview.github.client;

import java.util.Optional;

/**
 * 官方 GitHub MCP Server 的可替换 Client 端口。V1 只允许白名单读取；实现不可暴露写工具。
 */
public interface GithubReadOnlyMcpClient {

  Optional<String> execute(GithubMcpRequest request);
}
