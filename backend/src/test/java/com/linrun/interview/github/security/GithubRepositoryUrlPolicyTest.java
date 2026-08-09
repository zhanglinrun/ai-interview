package com.linrun.interview.github.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GitHub 仓库 URL allowlist")
class GithubRepositoryUrlPolicyTest {

  private final GithubRepositoryUrlPolicy policy = new GithubRepositoryUrlPolicy();

  @Test
  @DisplayName("只接受 github.com 的 HTTPS owner/repo 并规范化 URL")
  void shouldAcceptCanonicalPublicRepositoryUrl() {
    GithubRepositoryCoordinates result = policy.parse(
        "  https://github.com/spring-projects/spring-petclinic.git/  ");

    assertThat(result.owner()).isEqualTo("spring-projects");
    assertThat(result.repository()).isEqualTo("spring-petclinic");
    assertThat(result.canonicalUrl())
        .isEqualTo("https://github.com/spring-projects/spring-petclinic");
  }

  @Test
  @DisplayName("拒绝 SSRF、伪造 host、userinfo、端口、查询和额外路径")
  void shouldRejectSsrfAndAmbiguousUrls() {
    assertRejected("http://github.com/a/b");
    assertRejected("https://github.com.evil.example/a/b");
    assertRejected("https://127.0.0.1/a/b");
    assertRejected("https://user@github.com/a/b");
    assertRejected("https://github.com:443/a/b");
    assertRejected("https://github.com/a/b?redirect=http://127.0.0.1");
    assertRejected("https://github.com/a/b#readme");
    assertRejected("https://github.com/a/b/issues");
    assertRejected("https://github.com/a/../b");
  }

  private void assertRejected(String value) {
    assertThatThrownBy(() -> policy.parse(value))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.GITHUB_INVALID_REPOSITORY_URL.getCode());
  }
}
