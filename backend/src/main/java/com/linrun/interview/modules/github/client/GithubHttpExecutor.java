package com.linrun.interview.modules.github.client;

import java.util.List;
import java.util.Map;

/** 仅接受相对 API 路径的 HTTP 边界，防止业务数据变成可控目标地址。 */
public interface GithubHttpExecutor {

  GithubHttpResponse get(String relativePath);

  record GithubHttpResponse(int statusCode, Map<String, List<String>> headers, String body) {
    public String firstHeader(String name) {
      if (headers == null) {
        return null;
      }
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name))
          .flatMap(entry -> entry.getValue().stream())
          .findFirst()
          .orElse(null);
    }
  }
}
