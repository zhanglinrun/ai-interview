package com.linrun.interview.github.security;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 公共仓库 URL allowlist。只接受 {@code https://github.com/{owner}/{repo}}，从输入层阻断
 * 任意 URL 抓取、userinfo、非标准端口、私网 host、路径穿越和重定向型 SSRF。
 */
@Component
public class GithubRepositoryUrlPolicy {

  private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
  private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9._-]{1,100}");

  public GithubRepositoryCoordinates parse(String rawUrl) {
    if (rawUrl == null || rawUrl.isBlank()) {
      throw invalid("GitHub 仓库 URL 不能为空");
    }
    URI uri;
    try {
      uri = new URI(rawUrl.strip());
    } catch (URISyntaxException e) {
      throw invalid("GitHub 仓库 URL 格式非法");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || !"github.com".equals(uri.getHost().toLowerCase(Locale.ROOT))
        || uri.getPort() != -1
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw invalid("V1 仅支持 https://github.com/{owner}/{repo} 公共仓库 URL");
    }

    String path = uri.getPath();
    if (path == null) {
      throw invalid("GitHub 仓库 URL 缺少 owner/repo");
    }
    String normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    String[] parts = normalizedPath.split("/", -1);
    if (parts.length != 3 || !parts[0].isEmpty()) {
      throw invalid("GitHub 仓库 URL 必须只包含 owner/repo");
    }
    String owner = parts[1];
    String repository = parts[2].endsWith(".git")
        ? parts[2].substring(0, parts[2].length() - 4) : parts[2];
    if (!OWNER.matcher(owner).matches() || !REPOSITORY.matcher(repository).matches()
        || repository.equals(".") || repository.equals("..")) {
      throw invalid("GitHub owner 或 repository 格式非法");
    }
    return new GithubRepositoryCoordinates(
        owner,
        repository,
        "https://github.com/" + owner + "/" + repository);
  }

  private BusinessException invalid(String message) {
    return new BusinessException(ErrorCode.GITHUB_INVALID_REPOSITORY_URL, message);
  }
}
