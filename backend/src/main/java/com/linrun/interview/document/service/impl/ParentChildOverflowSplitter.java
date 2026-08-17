package com.linrun.interview.document.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 超长父章节的二次切分：先按更细标题，再按段落 / 代码块边界滑窗。
 *
 * <p>TITLE 默认按 1～3 级标题切段。某一级仍超长时若再按字符硬切，
 * 第一块子切片会变成父段前缀，检索互相稀释，预览也像重复。
 */
final class ParentChildOverflowSplitter {

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+\\S.*");
  private static final int PREAMBLE_MERGE_CHARS = 80;

  private ParentChildOverflowSplitter() {
  }

  /** 子块约为父节阈值的 40%，且必须小于父阈值，避免第一子块几乎复制整节。 */
  static int childChunkSize(int parentChunkSize) {
    if (parentChunkSize <= 1) {
      return parentChunkSize;
    }
    return Math.max(1, Math.min(parentChunkSize * 2 / 5, parentChunkSize - 1));
  }

  static List<String> splitChildren(String content, int chunkSize, int overlap) {
    if (content == null || content.isEmpty()) {
      return List.of();
    }
    if (chunkSize <= 0 || content.length() <= chunkSize) {
      return List.of(content);
    }
    int safeOverlap = Math.max(0, Math.min(overlap, Math.max(0, chunkSize - 1)));
    List<String> headingParts = splitByDeeperHeadings(content);
    if (headingParts.size() > 1) {
      List<String> result = new ArrayList<>();
      for (String part : headingParts) {
        if (part.isBlank()) {
          continue;
        }
        if (part.length() <= chunkSize) {
          result.add(part);
        } else {
          result.addAll(splitByBoundaries(part, chunkSize, safeOverlap));
        }
      }
      return result.isEmpty() ? List.of(content) : result;
    }
    return splitByBoundaries(content, chunkSize, safeOverlap);
  }

  static List<String> splitByDeeperHeadings(String content) {
    String[] lines = content.split("\n", -1);
    int baseLevel = firstHeadingLevel(lines);
    if (baseLevel <= 0 || baseLevel >= 6) {
      return List.of(content);
    }

    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inFence = false;
    for (String line : lines) {
      String trimmed = line.trim();
      if (isFence(trimmed)) {
        inFence = !inFence;
      }
      if (!inFence && isDeeperHeading(trimmed, baseLevel) && current.length() > 0) {
        parts.add(current.toString());
        current.setLength(0);
      }
      if (current.length() > 0) {
        current.append('\n');
      }
      current.append(line);
    }
    if (current.length() > 0) {
      parts.add(current.toString());
    }
    if (parts.size() <= 1) {
      return List.of(content);
    }
    return mergeTinyLeadingParts(parts);
  }

  private static List<String> splitByBoundaries(String content, int chunkSize, int overlap) {
    String heading = firstHeadingLine(content);
    List<String> parts = new ArrayList<>();
    int start = 0;
    int n = content.length();
    while (start < n) {
      if (n - start <= chunkSize) {
        parts.add(applyBreadcrumb(content.substring(start), heading, start > 0));
        break;
      }
      int hardEnd = start + chunkSize;
      int end = findSoftEnd(content, start, hardEnd, chunkSize);
      if (end <= start) {
        end = Math.min(start + chunkSize, n);
      }
      parts.add(applyBreadcrumb(content.substring(start, end), heading, start > 0));
      if (end >= n) {
        break;
      }
      int next = end - Math.min(overlap, end - start);
      next = snapToLineStart(content, next, end);
      if (next <= start) {
        next = end;
      }
      start = next;
    }
    return parts;
  }

  private static int findSoftEnd(String content, int start, int hardEnd, int chunkSize) {
    int n = content.length();
    hardEnd = Math.min(hardEnd, n);
    if (insideFence(content, hardEnd)) {
      int fenceStart = indexBeforeCurrentFence(content, hardEnd);
      int close = indexAfterClosingFence(content, hardEnd);
      int maxFence = Math.max(chunkSize * 2, 800);
      if (fenceStart > start) {
        return fenceStart;
      }
      if (close > start && fenceStart >= 0 && close - fenceStart <= maxFence) {
        return Math.min(close, n);
      }
    }
    int minEnd = start + Math.max(1, (int) ((hardEnd - start) * 0.7));
    int hr = content.lastIndexOf("\n---", hardEnd - 1);
    if (hr >= minEnd && !insideFence(content, hr)) {
      return indexAfterLine(content, hr);
    }
    int para = content.lastIndexOf("\n\n", hardEnd - 1);
    if (para >= minEnd && !insideFence(content, para)) {
      return para + 2;
    }
    int nl = content.lastIndexOf('\n', hardEnd - 1);
    if (nl >= minEnd && !insideFence(content, nl)) {
      return nl + 1;
    }
    for (int i = hardEnd - 1; i >= minEnd; i--) {
      if (Character.isWhitespace(content.charAt(i))) {
        return i + 1;
      }
    }
    return hardEnd;
  }

  private static List<String> mergeTinyLeadingParts(List<String> parts) {
    if (parts.size() < 2) {
      return parts;
    }
    String first = parts.get(0);
    if (!shouldMergePreamble(first)) {
      return parts;
    }
    List<String> merged = new ArrayList<>(parts.size() - 1);
    merged.add(first + "\n" + parts.get(1));
    merged.addAll(parts.subList(2, parts.size()));
    return merged;
  }

  private static boolean shouldMergePreamble(String part) {
    return isHeadingOrRuleOnly(part) || part.trim().length() <= PREAMBLE_MERGE_CHARS;
  }

  private static boolean isHeadingOrRuleOnly(String part) {
    for (String line : part.split("\n", -1)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || isRule(trimmed) || HEADING.matcher(trimmed).matches()) {
        continue;
      }
      return false;
    }
    return true;
  }

  private static String applyBreadcrumb(String piece, String heading, boolean continuation) {
    if (!continuation || heading == null || heading.isBlank()) {
      return piece;
    }
    if (piece.stripLeading().startsWith("#")) {
      return piece;
    }
    return heading + "\n" + piece;
  }

  private static String firstHeadingLine(String content) {
    boolean inFence = false;
    for (String line : content.split("\n", -1)) {
      String trimmed = line.trim();
      if (isFence(trimmed)) {
        inFence = !inFence;
        continue;
      }
      if (!inFence && HEADING.matcher(trimmed).matches()) {
        return trimmed;
      }
    }
    return null;
  }

  private static int firstHeadingLevel(String[] lines) {
    boolean inFence = false;
    for (String line : lines) {
      String trimmed = line.trim();
      if (isFence(trimmed)) {
        inFence = !inFence;
        continue;
      }
      if (inFence) {
        continue;
      }
      Matcher matcher = HEADING.matcher(trimmed);
      if (matcher.matches()) {
        return matcher.group(1).length();
      }
    }
    return 0;
  }

  private static boolean isDeeperHeading(String trimmed, int baseLevel) {
    Matcher matcher = HEADING.matcher(trimmed);
    return matcher.matches() && matcher.group(1).length() > baseLevel;
  }

  private static boolean isFence(String trimmed) {
    return trimmed.startsWith("```") || trimmed.startsWith("~~~");
  }

  private static boolean isRule(String trimmed) {
    return trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___");
  }

  private static boolean insideFence(String content, int pos) {
    boolean inFence = false;
    int i = 0;
    int n = Math.min(pos, content.length());
    while (i < n) {
      int nl = content.indexOf('\n', i);
      int lineEnd = nl < 0 || nl >= n ? n : nl;
      if (isFence(content.substring(i, lineEnd).trim())) {
        inFence = !inFence;
      }
      if (nl < 0 || nl >= n) {
        break;
      }
      i = nl + 1;
    }
    return inFence;
  }

  private static int indexAfterClosingFence(String content, int from) {
    int i = from;
    int n = content.length();
    if (i < n && content.charAt(i) != '\n') {
      int nl = content.indexOf('\n', i);
      i = nl < 0 ? n : nl + 1;
    }
    while (i < n) {
      int nl = content.indexOf('\n', i);
      int lineEnd = nl < 0 ? n : nl;
      if (isFence(content.substring(i, lineEnd).trim())) {
        return nl < 0 ? n : nl + 1;
      }
      if (nl < 0) {
        break;
      }
      i = nl + 1;
    }
    return -1;
  }

  private static int indexBeforeCurrentFence(String content, int pos) {
    boolean inFence = false;
    int fenceStart = -1;
    int i = 0;
    int n = Math.min(pos, content.length());
    while (i < n) {
      int nl = content.indexOf('\n', i);
      int lineEnd = nl < 0 || nl >= n ? n : nl;
      if (isFence(content.substring(i, lineEnd).trim())) {
        if (!inFence) {
          fenceStart = i;
        }
        inFence = !inFence;
      }
      if (nl < 0 || nl >= n) {
        break;
      }
      i = nl + 1;
    }
    return inFence ? fenceStart : -1;
  }

  private static int indexAfterLine(String content, int nlPos) {
    int lineStart = nlPos + 1;
    int lineEnd = content.indexOf('\n', lineStart);
    return lineEnd < 0 ? content.length() : lineEnd + 1;
  }

  private static int snapToLineStart(String content, int next, int end) {
    if (next <= 0 || next >= end) {
      return next;
    }
    if (content.charAt(next) == '\n') {
      return next + 1;
    }
    if (content.charAt(next - 1) == '\n') {
      return next;
    }
    int nl = content.indexOf('\n', next);
    if (nl >= 0 && nl < end) {
      return nl + 1;
    }
    return next;
  }
}
