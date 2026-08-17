package com.linrun.interview.document.service.impl;

import com.linrun.interview.infra.snowflake.SnowflakeIdGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.linrun.interview.rag.constant.MetadataKeyConstant.CHUNK_ID;
import static com.linrun.interview.rag.constant.MetadataKeyConstant.HEADER_LEVEL;

/**
 * 预处理标题段，不制造「整章父块」。
 *
 * <p>业界 Parent Document Retrieval：父块是一道题 / 一节，子块是节内检索粒。
 * 空的 {@code ## 基础} / {@code ## 概览} 没有正文，不能当 skipEmbedding 父块，
 * 只作为面包屑并入后续同级或更深的节；落在文末则并入上一节。
 */
final class ParentChildHierarchyLinker {

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+\\S.*");

  private ParentChildHierarchyLinker() {
  }

  static List<Section> link(List<Section> sections) {
    if (sections == null || sections.isEmpty()) {
      return List.of();
    }
    return absorbHeadingOnly(ensureChunkIds(sections));
  }

  private static List<Section> ensureChunkIds(List<Section> sections) {
    List<Section> result = new ArrayList<>(sections.size());
    for (Section section : sections) {
      Map<String, Object> metadata = new HashMap<>(section.metadata());
      if (blank(metadata.get(CHUNK_ID))) {
        metadata.put(CHUNK_ID, SnowflakeIdGenerator.getInstance().nextIdStr());
      }
      result.add(new Section(section.content(), metadata));
    }
    return result;
  }

  private static List<Section> absorbHeadingOnly(List<Section> sections) {
    List<Section> result = new ArrayList<>(sections.size());
    int i = 0;
    while (i < sections.size()) {
      if (!isHeadingOnly(sections.get(i).content())) {
        result.add(sections.get(i));
        i++;
        continue;
      }

      String prefix = firstHeadingLine(sections.get(i).content());
      Integer level = headerLevel(sections.get(i));
      int end = i + 1;
      while (end < sections.size()) {
        Integer memberLevel = headerLevel(sections.get(end));
        if (isHeadingOnly(sections.get(end).content())
            && level != null
            && level.equals(memberLevel)) {
          break;
        }
        if (memberLevel != null && level != null && memberLevel < level) {
          break;
        }
        end++;
      }
      if (end > i + 1) {
        for (int j = i + 1; j < end; j++) {
          result.add(withPrefix(sections.get(j), prefix));
        }
        i = end;
        continue;
      }
      if (!result.isEmpty()) {
        Section previous = result.remove(result.size() - 1);
        result.add(new Section(appendHeading(previous.content(), prefix), previous.metadata()));
      }
      i++;
    }
    return result;
  }

  private static Section withPrefix(Section section, String prefix) {
    if (prefix == null || prefix.isBlank()) {
      return section;
    }
    String content = section.content() == null ? "" : section.content();
    if (content.contains(prefix)) {
      return section;
    }
    return new Section(prefix + "\n" + content, new HashMap<>(section.metadata()));
  }

  private static String appendHeading(String content, String heading) {
    if (heading == null || heading.isBlank()) {
      return content;
    }
    if (content != null && content.contains(heading)) {
      return content;
    }
    return (content == null || content.isBlank() ? "" : content + "\n") + heading;
  }

  static boolean isCategoryHeader(String content, Integer level, Integer nextLevel) {
    return level != null && level.equals(nextLevel) && isHeadingOnly(content);
  }

  static boolean isHeadingOnly(String content) {
    if (content == null || content.isBlank()) {
      return false;
    }
    boolean seenHeading = false;
    for (String line : content.split("\n", -1)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || isRule(trimmed)) {
        continue;
      }
      if (HEADING.matcher(trimmed).matches()) {
        seenHeading = true;
        continue;
      }
      return false;
    }
    return seenHeading;
  }

  private static String firstHeadingLine(String content) {
    for (String line : content.split("\n", -1)) {
      String trimmed = line.trim();
      Matcher matcher = HEADING.matcher(trimmed);
      if (matcher.matches()) {
        return trimmed;
      }
    }
    return content == null ? "" : content.trim();
  }

  private static Integer headerLevel(Section section) {
    Object value = section.metadata().get(HEADER_LEVEL);
    if (value instanceof Number number) {
      return number.intValue();
    }
    return null;
  }

  private static boolean blank(Object value) {
    return value == null || String.valueOf(value).isBlank();
  }

  private static boolean isRule(String trimmed) {
    return trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___");
  }

  record Section(String content, Map<String, Object> metadata) {
  }
}
