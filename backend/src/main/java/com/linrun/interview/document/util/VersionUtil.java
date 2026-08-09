package com.linrun.interview.document.util;

/**
 * 语义化版本号工具（参考业界实现 VersionUtil）。
 */
public final class VersionUtil {

  private VersionUtil() {
  }

  /**
   * @return 负数 v1&lt;v2，0 相等，正数 v1&gt;v2
   */
  public static int compareVersions(String v1, String v2) {
    String[] parts1 = v1.split("\\.");
    String[] parts2 = v2.split("\\.");
    int maxLength = Math.max(parts1.length, parts2.length);
    for (int i = 0; i < maxLength; i++) {
      int p1 = i < parts1.length ? parsePart(parts1[i]) : 0;
      int p2 = i < parts2.length ? parsePart(parts2[i]) : 0;
      if (p1 != p2) {
        return Integer.compare(p1, p2);
      }
    }
    return 0;
  }

  private static int parsePart(String part) {
    try {
      return Integer.parseInt(part.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
