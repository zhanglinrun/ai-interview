package com.linrun.interview.modules.github.mcp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.modules.github.model.GithubFileStatus;
import com.linrun.interview.modules.github.model.GithubRepositoryEntity;
import com.linrun.interview.modules.github.model.GithubRepositoryFileEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GitHub MCP 只读白名单")
class GithubMcpWhitelistTest {

  private static final String SHA = "a".repeat(40);
  private GithubMcpWhitelist whitelist;
  private GithubRepositoryEntity repository;
  private GithubRepositoryFileEntity file;

  @BeforeEach
  void setUp() {
    whitelist = new GithubMcpWhitelist();
    repository = GithubRepositoryEntity.builder()
        .ownerName("demo")
        .repositoryName("repo")
        .fixedCommitSha(SHA)
        .build();
    file = GithubRepositoryFileEntity.builder()
        .path("src/App.java")
        .commitSha(SHA)
        .status(GithubFileStatus.SYNCED)
        .build();
  }

  @Test
  @DisplayName("只允许绑定 owner/repo/SHA/path 下的读取")
  void shouldAllowExactReadScope() {
    GithubMcpRequest request = new GithubMcpRequest(
        "get_file_contents", "demo", "repo", SHA, "src/App.java");

    assertThatCode(() -> whitelist.validate(request, repository, file))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("拒绝写工具、其他仓库、其他 SHA 和路径穿越")
  void shouldRejectScopeExpansionAndWrites() {
    assertForbidden(new GithubMcpRequest("create_issue", "demo", "repo", SHA, "src/App.java"));
    assertForbidden(new GithubMcpRequest("get_file_contents", "other", "repo", SHA, "src/App.java"));
    assertForbidden(new GithubMcpRequest(
        "get_file_contents", "demo", "repo", "b".repeat(40), "src/App.java"));
    assertForbidden(new GithubMcpRequest("get_file_contents", "demo", "repo", SHA, "../.env"));
  }

  private void assertForbidden(GithubMcpRequest request) {
    assertThatThrownBy(() -> whitelist.validate(request, repository, file))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("GitHub MCP");
  }
}
