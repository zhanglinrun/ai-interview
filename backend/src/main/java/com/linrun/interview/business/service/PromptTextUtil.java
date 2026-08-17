package com.linrun.interview.business.service;

/**
 * Prompt 注入用文本裁剪：优先头尾保留，避免长答结论落在后半被砍掉。
 */
public final class PromptTextUtil {

  private static final String ELLIPSIS = "…";

  private PromptTextUtil() {
  }

  /**
   * 长度未超限原样返回；超限时保留前 {@code headChars} 与后 {@code tailChars}，中间插入省略号。
   * {@code maxChars} 为最终目标上限（含省略号），头尾按约 2:1 分配，至少各留 32 字。
   */
  public static String headTailTruncate(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    String normalized = text.strip();
    if (maxChars <= 0 || normalized.length() <= maxChars) {
      return normalized;
    }
    if (maxChars <= ELLIPSIS.length() + 2) {
      return normalized.substring(0, Math.min(normalized.length(), maxChars));
    }
    int budget = maxChars - ELLIPSIS.length();
    int headChars = Math.max(32, (budget * 2) / 3);
    int tailChars = Math.max(32, budget - headChars);
    if (headChars + tailChars > budget) {
      tailChars = Math.max(1, budget - headChars);
    }
    if (headChars + tailChars >= normalized.length()) {
      return normalized.substring(0, maxChars);
    }
    return normalized.substring(0, headChars)
        + ELLIPSIS
        + normalized.substring(normalized.length() - tailChars);
  }
}
