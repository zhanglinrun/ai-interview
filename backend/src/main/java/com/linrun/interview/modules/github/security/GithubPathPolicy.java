package com.linrun.interview.modules.github.security;

import java.util.Arrays;

/** 仓库内相对路径校验，供 REST 清单、用户选择和 MCP 白名单共同复用。 */
public final class GithubPathPolicy {

  private GithubPathPolicy() {
  }

  public static boolean isSafe(String path) {
    if (path == null || path.isBlank() || path.length() > 500
        || path.startsWith("/") || path.endsWith("/") || path.contains("\\")) {
      return false;
    }
    if (path.chars().anyMatch(character -> Character.isISOControl(character))) {
      return false;
    }
    return Arrays.stream(path.split("/", -1))
        .allMatch(segment -> !segment.isBlank() && !segment.equals(".") && !segment.equals(".."));
  }

  public static boolean isWithin(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }
}
