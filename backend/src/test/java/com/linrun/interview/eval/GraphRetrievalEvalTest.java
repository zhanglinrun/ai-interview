package com.linrun.interview.eval;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图谱检索评测 runner（P1 图谱可用性证据）。
 *
 * <p>只依赖 Neo4j（不启动完整 Spring 上下文）：校验技能图谱
 * {@code (:Skill)-[:COVERS]->(:Concept)} 已预置且可被检索，量化「每个技能覆盖的概念数」这一
 * 图检索召回代理指标——这是 Text2Cypher 图检索能返回有效上下文的前提。
 *
 * <p>连真实 Neo4j，默认被 {@code graph-eval} 组排除，不随普通 {@code mvn test} 运行。运行：
 * <pre>
 * 前置：cd dev-ops && docker compose -f docker-compose-environment.yml up -d   # 起 Neo4j
 * 启动一次后端（触发 SkillGraphBootstrap 预置技能图谱），或手动导入图数据
 * export NEO4J_URI=bolt://localhost:27687 NEO4J_USER=neo4j NEO4J_PASSWORD=neo4j666
 * mvn -pl backend test -Dtest=GraphRetrievalEvalTest -Dtest.excludedGroups= -Dgroups=graph-eval
 * </pre>
 *
 * <p>报告写到 {@code GRAPH_EVAL_REPORT}（默认 {@code ../eval/.work/graph-eval-report.md}）。
 * 设置 {@code GRAPH_EVAL_MIN_SKILLS} / {@code GRAPH_EVAL_MIN_COVERAGE} 后低于阈值断言失败（供 CI 门禁）。
 */
@Tag("graph-eval")
@DisplayName("图谱检索评测（技能→概念 覆盖度）")
class GraphRetrievalEvalTest {

  private static final Logger log = LoggerFactory.getLogger(GraphRetrievalEvalTest.class);
  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  @Test
  @DisplayName("技能图谱已预置且每个技能均覆盖概念")
  void evaluateSkillGraphCoverage() {
    String uri = env("NEO4J_URI", "bolt://localhost:7687");
    String user = env("NEO4J_USER", "neo4j");
    String password = env("NEO4J_PASSWORD", "neo4j666");

    Driver driver = tryConnect(uri, user, password);
    Assumptions.assumeTrue(driver != null, "Neo4j 不可达，跳过图谱检索评测: " + uri);

    try (Driver d = driver; Session session = d.session()) {
      long skillCount = session.executeRead(tx ->
          tx.run("MATCH (s:Skill) RETURN count(s) AS c").single().get("c").asLong());
      Assumptions.assumeTrue(skillCount > 0,
          "技能图谱为空（未启动后端触发 SkillGraphBootstrap？），跳过评测");

      // 每个技能覆盖的概念数（图检索召回代理指标）
      Map<String, Long> coverage = new LinkedHashMap<>();
      session.executeRead(tx -> {
        var result = tx.run(
            "MATCH (s:Skill) "
                + "OPTIONAL MATCH (s)-[:COVERS]->(c:Concept) "
                + "RETURN s.name AS skill, count(c) AS concepts ORDER BY concepts DESC");
        while (result.hasNext()) {
          var record = result.next();
          coverage.put(record.get("skill").asString("?"), record.get("concepts").asLong());
        }
        return null;
      });

      long coveredSkills = coverage.values().stream().filter(n -> n > 0).count();
      double coverageRate = coverage.isEmpty() ? 0.0 : (double) coveredSkills / coverage.size();
      double avgConcepts = coverage.isEmpty() ? 0.0
          : coverage.values().stream().mapToLong(Long::longValue).average().orElse(0.0);

      long conceptTotal = session.executeRead(tx ->
          tx.run("MATCH (c:Concept) RETURN count(c) AS c").single().get("c").asLong());
      long relatesEdges = session.executeRead(tx ->
          tx.run("MATCH (:Concept)-[r:RELATES_TO]->(:Concept) RETURN count(r) AS c")
              .single().get("c").asLong());

      writeReport(uri, skillCount, conceptTotal, relatesEdges, coverageRate, avgConcepts, coverage);

      log.info("[GraphEval] skills={}, concepts={}, coveredSkills={}, coverageRate={}, avgConcepts={}",
          skillCount, conceptTotal, coveredSkills, coverageRate, avgConcepts);

      int minSkills = intEnv("GRAPH_EVAL_MIN_SKILLS", 1);
      double minCoverage = doubleEnv("GRAPH_EVAL_MIN_COVERAGE", 1.0);
      assertThat(skillCount).as("技能节点数").isGreaterThanOrEqualTo(minSkills);
      assertThat(coverageRate).as("技能覆盖概念比例").isGreaterThanOrEqualTo(minCoverage);
    }
  }

  private void writeReport(String uri, long skills, long concepts, long relates,
                           double coverageRate, double avgConcepts, Map<String, Long> coverage) {
    StringBuilder sb = new StringBuilder();
    sb.append("# 图谱检索评测报告\n\n");
    sb.append("- 时间：").append(LocalDateTime.now().format(TS)).append('\n');
    sb.append("- Neo4j：").append(uri).append('\n');
    sb.append("- Skill 节点：").append(skills).append('\n');
    sb.append("- Concept 节点：").append(concepts).append('\n');
    sb.append("- RELATES_TO 边：").append(relates).append('\n');
    sb.append("- 技能覆盖概念比例：").append(String.format("%.1f%%", coverageRate * 100)).append('\n');
    sb.append("- 平均每技能覆盖概念数：").append(String.format("%.2f", avgConcepts)).append("\n\n");
    sb.append("| 技能 | 覆盖概念数 |\n| --- | ---: |\n");
    coverage.forEach((skill, n) -> sb.append("| ").append(skill).append(" | ").append(n).append(" |\n"));

    String reportPath = env("GRAPH_EVAL_REPORT", "../eval/.work/graph-eval-report.md");
    try {
      Path path = Paths.get(reportPath);
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
      log.info("[GraphEval] 报告已写入: {}", path.toAbsolutePath());
    } catch (Exception e) {
      log.warn("[GraphEval] 报告写入失败（不影响断言）: {}", e.getMessage());
    }
  }

  private Driver tryConnect(String uri, String user, String password) {
    try {
      Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
      driver.verifyConnectivity();
      return driver;
    } catch (Exception e) {
      log.warn("[GraphEval] 连接 Neo4j 失败: {}", e.getMessage());
      return null;
    }
  }

  private static String env(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static int intEnv(String key, int fallback) {
    try {
      return Integer.parseInt(env(key, String.valueOf(fallback)));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static double doubleEnv(String key, double fallback) {
    try {
      return Double.parseDouble(env(key, String.valueOf(fallback)));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  // 保留：未来可扩展为“问题→期望概念”检索式评测（需 ChatModel + Text2Cypher）
  @SuppressWarnings("unused")
  private List<String> placeholderForRetrievalCases() {
    return new ArrayList<>();
  }
}
