package com.linrun.interview.modules.knowledgebase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动时从 skills 目录各子目录 SKILL.md 预置 Skill 节点与 COVERS 关系（对齐 know-engine 技能图谱）。
 */
@Slf4j
@Component
@ConditionalOnBean(Driver.class)
@RequiredArgsConstructor
public class SkillGraphBootstrapService implements ApplicationRunner {

  private static final Pattern NAME_PATTERN = Pattern.compile("^name:\\s*(.+)$", Pattern.MULTILINE);
  private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,3})\\s+(.+)$", Pattern.MULTILINE);

  private final KnowledgeBaseQueryProperties queryProperties;

  @Autowired(required = false)
  private Driver neo4jDriver;

  @Override
  public void run(ApplicationArguments args) {
    if (neo4jDriver == null || !queryProperties.getGraph().isEnabled()
        || !queryProperties.getGraph().isSkillBootstrapEnabled()) {
      return;
    }
    try {
      PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
      Resource[] resources = resolver.getResources("classpath:skills/*/SKILL.md");
      int skillCount = 0;
      for (Resource resource : resources) {
        if (bootstrapSkill(resource)) {
          skillCount++;
        }
      }
      log.info("[SkillGraphBootstrap] 预置技能图谱完成: skills={}", skillCount);
    } catch (Exception e) {
      log.warn("[SkillGraphBootstrap] 技能图谱预置失败: {}", e.getMessage(), e);
    }
  }

  private boolean bootstrapSkill(Resource resource) throws Exception {
    String content = resource.getContentAsString(StandardCharsets.UTF_8);
    String skillName = parseSkillName(content);
    if (skillName == null || skillName.isBlank()) {
      return false;
    }
    Set<String> topics = parseTopics(content);
    if (topics.isEmpty()) {
      topics.add(skillName);
    }
    writeSkillGraph(skillName, new ArrayList<>(topics));
    return true;
  }

  private String parseSkillName(String content) {
    Matcher matcher = NAME_PATTERN.matcher(content);
    if (matcher.find()) {
      return matcher.group(1).trim();
    }
    return null;
  }

  private Set<String> parseTopics(String content) {
    Set<String> topics = new LinkedHashSet<>();
    Matcher matcher = HEADER_PATTERN.matcher(content);
    while (matcher.find()) {
      String title = matcher.group(2).trim();
      if (!title.isBlank() && title.length() <= 80 && !"Overview".equalsIgnoreCase(title)) {
        topics.add(title);
      }
    }
    return topics;
  }

  private void writeSkillGraph(String skillName, List<String> topics) {
    try (Session session = neo4jDriver.session()) {
      session.executeWrite(tx -> {
        tx.run("""
            MERGE (s:Skill {name: $name})
            SET s.source = 'skill-bootstrap', s.updatedAt = datetime()
            """, Map.of("name", skillName));
        for (String topic : topics) {
          tx.run("""
              MERGE (c:Concept {name: $concept})
              WITH c
              MATCH (s:Skill {name: $skill})
              MERGE (s)-[:COVERS]->(c)
              """, Map.of("concept", topic, "skill", skillName));
        }
        return null;
      });
    }
  }
}
