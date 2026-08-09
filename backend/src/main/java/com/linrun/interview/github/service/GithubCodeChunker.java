package com.linrun.interview.github.service;

import com.linrun.interview.github.model.CodeChunk;
import com.linrun.interview.github.config.GithubEvidenceProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 轻量代码感知切块：Java 识别类/接口/枚举/record 与方法，Python 识别缩进符号，
 * JS/TS/Go/Rust/C 系按函数或类花括号识别，最后统一退化为重叠行窗口。
 */
@Component
public class GithubCodeChunker {

  private static final Pattern JAVA_TYPE = Pattern.compile(
      "\\b(class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
  private static final Pattern JAVA_CALLABLE = Pattern.compile(
      "([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[^{}]+)?(?:\\{|$)");
  private static final Set<String> JAVA_CONTROL = Set.of(
      "if", "for", "while", "switch", "catch", "try", "synchronized", "return", "throw", "new");
  private static final Pattern PYTHON_SYMBOL = Pattern.compile(
      "^(\\s*)(?:async\\s+def|def|class)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
  private static final Pattern BRACE_SYMBOL = Pattern.compile(
      "\\b(?:class|interface|struct|enum|trait|impl|function|func|fn)\\s+"
          + "([A-Za-z_$][A-Za-z0-9_$]*)|"
          + "\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*[:=]?\\s*(?:async\\s*)?"
          + "(?:function\\s*)?\\([^;{}]*\\)\\s*(?:=>)?\\s*\\{");

  private final GithubEvidenceProperties properties;

  public GithubCodeChunker(GithubEvidenceProperties properties) {
    this.properties = properties;
  }

  public List<CodeChunk> chunk(String path, String language, String content) {
    if (content == null || content.isBlank()) {
      return List.of();
    }
    String[] lines = content.split("\\n", -1);
    List<SymbolRegion> regions;
    if ("Java".equalsIgnoreCase(language)) {
      regions = javaRegions(lines);
    } else if ("Python".equalsIgnoreCase(language)) {
      regions = pythonRegions(lines);
    } else if (isBraceLanguage(language)) {
      regions = braceRegions(lines);
    } else {
      regions = List.of();
    }
    if (regions.isEmpty()) {
      return windows(path, "FILE_WINDOW", lines, 0, lines.length - 1);
    }

    Map<String, CodeChunk> unique = new LinkedHashMap<>();
    regions.stream()
        .sorted(Comparator.comparingInt(SymbolRegion::startLine)
            .thenComparingInt(SymbolRegion::endLine))
        .forEach(region -> windows(region.name(), region.kind(), lines,
            region.startLine(), region.endLine()).forEach(chunk ->
                unique.putIfAbsent(chunk.startLine() + ":" + chunk.endLine() + ":"
                    + chunk.symbolName(), chunk)));
    return List.copyOf(unique.values());
  }

  public String summarizeFile(String path, String language, List<CodeChunk> chunks) {
    List<String> symbols = chunks.stream()
        .map(CodeChunk::symbolName)
        .filter(name -> name != null && !name.isBlank() && !name.equals(path))
        .distinct()
        .limit(12)
        .toList();
    String summary = path + "（" + (language == null ? "Text" : language) + "）";
    return symbols.isEmpty() ? summary : summary + "；符号：" + String.join("、", symbols);
  }

  private List<SymbolRegion> javaRegions(String[] lines) {
    String[] sanitized = sanitizeJava(lines);
    List<SymbolRegion> regions = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int lineIndex = 0; lineIndex < sanitized.length; lineIndex++) {
      String line = sanitized[lineIndex];
      Matcher typeMatcher = JAVA_TYPE.matcher(line);
      while (typeMatcher.find()) {
        addBraceRegion(regions, seen, sanitized, lineIndex, typeMatcher.group(2),
            typeMatcher.group(1).toUpperCase(Locale.ROOT));
      }
      Matcher callableMatcher = JAVA_CALLABLE.matcher(line);
      while (callableMatcher.find()) {
        String name = callableMatcher.group(1);
        if (!JAVA_CONTROL.contains(name)) {
          addBraceRegion(regions, seen, sanitized, lineIndex, name, "METHOD");
        }
      }
    }
    return regions;
  }

  private List<SymbolRegion> pythonRegions(String[] lines) {
    List<SymbolRegion> regions = new ArrayList<>();
    for (int index = 0; index < lines.length; index++) {
      Matcher matcher = PYTHON_SYMBOL.matcher(lines[index]);
      if (!matcher.find()) {
        continue;
      }
      int indent = indentation(matcher.group(1));
      int end = lines.length - 1;
      for (int next = index + 1; next < lines.length; next++) {
        if (lines[next].isBlank() || lines[next].stripLeading().startsWith("#")) {
          continue;
        }
        if (indentation(lines[next]) <= indent) {
          end = next - 1;
          break;
        }
      }
      String kind = lines[index].stripLeading().startsWith("class ") ? "CLASS" : "FUNCTION";
      regions.add(new SymbolRegion(matcher.group(2), kind, index, Math.max(index, end)));
    }
    return regions;
  }

  private List<SymbolRegion> braceRegions(String[] lines) {
    String[] sanitized = sanitizeJava(lines);
    List<SymbolRegion> regions = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int index = 0; index < sanitized.length; index++) {
      Matcher matcher = BRACE_SYMBOL.matcher(sanitized[index]);
      while (matcher.find()) {
        String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        if (name != null && !JAVA_CONTROL.contains(name)) {
          addBraceRegion(regions, seen, sanitized, index, name, "SYMBOL");
        }
      }
    }
    return regions;
  }

  private void addBraceRegion(
      List<SymbolRegion> regions,
      Set<String> seen,
      String[] lines,
      int declarationLine,
      String name,
      String kind
  ) {
    int openLine = -1;
    int openColumn = -1;
    for (int line = declarationLine; line < Math.min(lines.length, declarationLine + 9); line++) {
      int column = lines[line].indexOf('{');
      if (column >= 0) {
        openLine = line;
        openColumn = column;
        break;
      }
      if (lines[line].contains(";")) {
        break;
      }
    }
    if (openLine < 0) {
      return;
    }
    int depth = 0;
    for (int line = openLine; line < lines.length; line++) {
      int start = line == openLine ? openColumn : 0;
      for (int column = start; column < lines[line].length(); column++) {
        char character = lines[line].charAt(column);
        if (character == '{') {
          depth++;
        } else if (character == '}') {
          depth--;
          if (depth == 0) {
            String key = declarationLine + ":" + line + ":" + name;
            if (seen.add(key)) {
              regions.add(new SymbolRegion(name, kind, declarationLine, line));
            }
            return;
          }
        }
      }
    }
  }

  private List<CodeChunk> windows(
      String symbolName,
      String symbolKind,
      String[] lines,
      int regionStart,
      int regionEnd
  ) {
    if (regionStart < 0 || regionEnd < regionStart || regionStart >= lines.length) {
      return List.of();
    }
    int maxLines = Math.max(1, properties.getChunkMaxLines());
    int overlap = Math.min(Math.max(0, properties.getChunkOverlapLines()), maxLines - 1);
    List<CodeChunk> chunks = new ArrayList<>();
    int part = 1;
    int start = regionStart;
    while (start <= regionEnd) {
      int end = Math.min(regionEnd, start + maxLines - 1);
      String text = join(lines, start, end);
      if (text.length() > properties.getChunkMaxChars()) {
        while (end > start && text.length() > properties.getChunkMaxChars()) {
          end--;
          text = join(lines, start, end);
        }
        if (text.length() > properties.getChunkMaxChars()) {
          text = text.substring(0, properties.getChunkMaxChars());
        }
      }
      String chunkName = regionEnd - regionStart + 1 > maxLines
          ? symbolName + "#part" + part : symbolName;
      chunks.add(new CodeChunk(chunkName, symbolKind, start + 1, end + 1, text));
      if (end >= regionEnd) {
        break;
      }
      start = Math.max(start + 1, end - overlap + 1);
      part++;
    }
    return chunks;
  }

  private String[] sanitizeJava(String[] lines) {
    String[] result = new String[lines.length];
    boolean blockComment = false;
    for (int index = 0; index < lines.length; index++) {
      StringBuilder sanitized = new StringBuilder(lines[index].length());
      boolean quoted = false;
      char quote = 0;
      boolean escaped = false;
      for (int column = 0; column < lines[index].length(); column++) {
        char current = lines[index].charAt(column);
        char next = column + 1 < lines[index].length() ? lines[index].charAt(column + 1) : 0;
        if (blockComment) {
          sanitized.append(' ');
          if (current == '*' && next == '/') {
            sanitized.append(' ');
            column++;
            blockComment = false;
          }
          continue;
        }
        if (quoted) {
          sanitized.append(' ');
          if (!escaped && current == quote) {
            quoted = false;
          }
          escaped = !escaped && current == '\\';
          if (current != '\\') {
            escaped = false;
          }
          continue;
        }
        if (current == '/' && next == '/') {
          sanitized.append(" ".repeat(lines[index].length() - column));
          break;
        }
        if (current == '/' && next == '*') {
          sanitized.append("  ");
          column++;
          blockComment = true;
          continue;
        }
        if (current == '\'' || current == '"') {
          sanitized.append(' ');
          quoted = true;
          quote = current;
          escaped = false;
          continue;
        }
        sanitized.append(current);
      }
      result[index] = sanitized.toString();
    }
    return result;
  }

  private int indentation(String text) {
    int indent = 0;
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character == ' ') {
        indent++;
      } else if (character == '\t') {
        indent += 4;
      } else {
        break;
      }
    }
    return indent;
  }

  private String join(String[] lines, int start, int end) {
    StringBuilder result = new StringBuilder();
    for (int index = start; index <= end; index++) {
      if (index > start) {
        result.append('\n');
      }
      result.append(lines[index]);
    }
    return result.toString();
  }

  private boolean isBraceLanguage(String language) {
    return Set.of("JavaScript", "TypeScript", "Go", "Rust", "C", "C++", "C#", "Kotlin",
            "Scala", "Swift", "PHP")
        .contains(language);
  }

  private record SymbolRegion(String name, String kind, int startLine, int endLine) {
  }
}
