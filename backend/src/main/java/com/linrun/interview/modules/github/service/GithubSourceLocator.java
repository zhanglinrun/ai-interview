package com.linrun.interview.modules.github.service;

import java.net.URI;
import java.net.URISyntaxException;

final class GithubSourceLocator {

  private GithubSourceLocator() {
  }

  static String blob(String baseUrl, String sha, String path, int startLine, int endLine) {
    try {
      URI base = URI.create(baseUrl);
      String encodedPath = "/blob/" + sha + "/" + path;
      URI locator = new URI(base.getScheme(), base.getAuthority(), encodedPath,
          null, "L" + startLine + "-L" + endLine);
      return locator.toASCIIString();
    } catch (URISyntaxException | IllegalArgumentException e) {
      // path 已通过 GithubPathPolicy 校验；这里只保留安全的可定位字符串作为最终兜底。
      return baseUrl + "/blob/" + sha + "/" + path + "#L" + startLine + "-L" + endLine;
    }
  }
}
