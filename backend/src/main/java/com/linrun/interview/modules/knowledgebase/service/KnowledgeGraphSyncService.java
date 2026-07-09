package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库向量化完成后，从分段标题抽取概念节点写入 Neo4j（对齐业界实践 图检索数据闭环）。
 */
@Slf4j
@Service
@ConditionalOnBean(Driver.class)
@RequiredArgsConstructor
public class KnowledgeGraphSyncService {

  private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,4})\\s+(.+)$", Pattern.MULTILINE);

  /**
   * 概念黑名单：文档模板/说明性小节标题（如简历模板里的 "Instructions"、"Additional Requirements"）
   * 不是知识概念，入图只会产生无意义节点。
   */
  private static final Set<String> CONCEPT_STOPWORDS = Set.of(
      "instructions", "instruction", "additional requirements", "additional requirement",
      "additional resources", "additional resource", "resources", "resource",
      "requirements", "requirement", "notes", "note", "summary", "overview", "introduction",
      "目录", "说明", "备注", "简介", "概述", "前言", "附录", "参考资料", "参考文献");

  private static boolean isMeaningfulConcept(String title) {
    if (title == null) {
      return false;
    }
    String normalized = title.strip().toLowerCase();
    if (normalized.isBlank() || normalized.length() > 80) {
      return false;
    }
    // 纯数字/纯标点的标题（如 "1."、"---"）没有概念含义
    if (normalized.replaceAll("[\\d\\s\\p{Punct}]+", "").isBlank()) {
      return false;
    }
    return !CONCEPT_STOPWORDS.contains(normalized);
  }

  private final KnowledgeBaseQueryProperties queryProperties;
  private final KnowledgeSegmentService segmentService;

  @Autowired(required = false)
  private Driver neo4jDriver;

  public boolean isEnabled() {
    return queryProperties.getGraph().isEnabled()
        && queryProperties.getGraph().isAutoSyncOnVectorize()
        && neo4jDriver != null;
  }

  public void syncDocument(Long docId, Long versionId, Long userId, String docName) {
    if (!isEnabled() || docId == null || versionId == null) {
      return;
    }
    List<KnowledgeBaseSegmentEntity> segments = segmentService.findByVersionId(versionId);
    if (segments == null || segments.isEmpty()) {
      return;
    }
    Set<String> concepts = new LinkedHashSet<>();
    for (KnowledgeBaseSegmentEntity segment : segments) {
      extractConcepts(segment.getText()).forEach(concepts::add);
    }
    if (concepts.isEmpty()) {
      for (KnowledgeBaseSegmentEntity segment : segments) {
        extractConceptFallback(segment.getText()).forEach(concepts::add);
      }
    }
    if (concepts.isEmpty()) {
      log.info("[KnowledgeGraphSync] 无标题概念可同步: docId={}, versionId={}", docId, versionId);
      return;
    }
    try {
      writeGraph(docId, versionId, userId, docName, new ArrayList<>(concepts));
      log.info("[KnowledgeGraphSync] 图数据同步完成: docId={}, versionId={}, concepts={}",
          docId, versionId, concepts.size());
    } catch (Exception e) {
      log.warn("[KnowledgeGraphSync] 图数据同步失败: docId={}, versionId={}, error={}",
          docId, versionId, e.getMessage(), e);
    }
  }

  private Set<String> extractConcepts(String text) {
    Set<String> concepts = new LinkedHashSet<>();
    if (text == null || text.isBlank()) {
      return concepts;
    }
    Matcher matcher = HEADER_PATTERN.matcher(text);
    while (matcher.find()) {
      String title = matcher.group(2).trim();
      if (isMeaningfulConcept(title)) {
        concepts.add(title);
      }
    }
    return concepts;
  }

  private Set<String> extractConceptFallback(String text) {
    Set<String> concepts = new LinkedHashSet<>();
    if (text == null || text.isBlank()) {
      return concepts;
    }
    String firstLine = text.lines()
        .map(String::trim)
        .filter(line -> !line.isBlank())
        .findFirst()
        .orElse("");
    if (!firstLine.startsWith("#") && isMeaningfulConcept(firstLine)) {
      concepts.add(firstLine);
    }
    Matcher boldMatcher = Pattern.compile("\\*\\*(.+?)\\*\\*").matcher(text);
    while (boldMatcher.find() && concepts.size() < 8) {
      String term = boldMatcher.group(1).trim();
      if (isMeaningfulConcept(term)) {
        concepts.add(term);
      }
    }
    return concepts;
  }

  private void writeGraph(Long docId, Long versionId, Long userId, String docName,
                          List<String> concepts) {
    try (Session session = neo4jDriver.session()) {
      session.executeWrite(tx -> {
        tx.run("""
            MERGE (d:Document {docId: $docId, versionId: $versionId})
            SET d.name = $docName,
                d.userId = $userId,
                d.updatedAt = datetime()
            """,
            Map.of(
                "docId", docId,
                "versionId", versionId,
                "docName", docName == null ? "" : docName,
                "userId", userId == null ? 0L : userId));
        for (String concept : concepts) {
          tx.run("""
              MERGE (c:Concept {name: $name})
              WITH c
              MATCH (d:Document {docId: $docId, versionId: $versionId})
              MERGE (c)-[:BELONGS_TO]->(d)
              """,
              Map.of("name", concept, "docId", docId, "versionId", versionId));
        }
        for (int i = 1; i < concepts.size(); i++) {
          tx.run("""
              MATCH (a:Concept {name: $from})
              MATCH (b:Concept {name: $to})
              MERGE (a)-[:RELATES_TO {docId: $docId}]->(b)
              """,
              Map.of(
                  "from", concepts.get(i - 1),
                  "to", concepts.get(i),
                  "docId", docId));
        }
        return null;
      });
    }
  }
}
