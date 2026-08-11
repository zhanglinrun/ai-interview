package com.linrun.interview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.rag.config.ElasticSearchProperties;
import com.linrun.interview.rag.constant.MetadataKeyConstant;
import com.linrun.interview.rag.service.ContextExpansionService;
import com.linrun.interview.rag.service.InterviewElasticsearchContentRetriever;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import com.linrun.interview.document.service.KnowledgeSegmentService;
import com.linrun.interview.rag.service.RerankService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * RAG 检索评测 runner（P4 评测闭环落地）。
 *
 * <p>在固定语料下量化对比 <b>vector / hybrid / hybrid+rerank / hybrid+rerank+expand</b>
 * 四档检索策略的召回与排序质量，
 * 为简历指标提供可复现证据。数据集见 {@code eval/rag-retrieval/eval-dataset.yaml}
 * （pom 复制到测试 classpath {@code rag-eval/eval-dataset.yaml}），四档策略经
 * {@link InterviewElasticsearchContentRetriever} 的 {@code forcedSearchMode} 分档构造，
 * hybrid+rerank 档再用 {@link RerankService} 精排。
 *
 * <p>前三档关闭父子/兄弟上下文扩展，只比较 chunk 级命中；第四档严格复用生产顺序：
 * 子块混合召回 → 子块 rerank → TopK → small-to-big 扩展。不设最低分阈值，正确性判定沿用
 * 数据集「关键点同义词组」ground truth。
 *
 * <p>连真实 ES + DashScope，默认被 {@code rag-eval} 组排除，不随普通 {@code mvn test} 运行。运行：
 * <pre>
 * 前置：cd dev-ops && docker compose -f docker-compose-environment.yml up -d   # ES / MySQL / Redis
 * # 语料入库后把知识库 id 写入环境变量
 * export RAGEVAL_KB_REDIS=.. RAGEVAL_KB_MYSQL=.. RAGEVAL_KB_DISTRIBUTED=..
 * mvn -pl backend test -Dtest=RagRetrievalEvalTest -Dtest.excludedGroups= -Dgroups=rag-eval
 * </pre>
 *
 * <p>报告写到 {@code ragdataset.report-path}（默认 {@code eval/.work/rag-retrieval-report.md}）。
 * 设置 {@code RAGEVAL_MIN_COVERAGE} 后，最优档关键点覆盖率低于阈值时断言失败（供 CI 门禁）。
 */
@Tag("rag-eval")
@ActiveProfiles("rageval")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("RAG 检索评测（vector / hybrid / hybrid+rerank / expand 四档对比）")
class RagRetrievalEvalTest {

  private static final Logger log = LoggerFactory.getLogger(RagRetrievalEvalTest.class);
  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  @Autowired private ElasticsearchEmbeddingStore embeddingStore;
  @Autowired private LlmProviderRegistry llmProviderRegistry;
  @Autowired private RerankService rerankService;
  @Autowired private KnowledgeSegmentService segmentService;
  @Autowired private KnowledgeBaseQueryProperties queryProperties;
  @Autowired private ElasticSearchProperties elasticSearchProperties;
  @Autowired private RestClient restClient;
  @Autowired private ObjectMapper objectMapper;

  @Value("${ragdataset.dataset-path:classpath:rag-eval/eval-dataset.yaml}")
  private String datasetPath;
  @Value("${ragdataset.top-k:6}")
  private int evalTopK;
  @Value("${ragdataset.candidate-top-k:20}")
  private int candidateTopK;
  @Value("${ragdataset.report-path:eval/.work/rag-retrieval-report.md}")
  private String reportPath;
  @Value("${ragdataset.kb-redis:0}")
  private long kbRedis;
  @Value("${ragdataset.kb-mysql:0}")
  private long kbMysql;
  @Value("${ragdataset.kb-distributed:0}")
  private long kbDistributed;
  @Value("${ragdataset.kb-jvm:0}")
  private long kbJvm;
  @Value("${ragdataset.kb-spring:0}")
  private long kbSpring;

  /** 四档检索策略（第四档在第三档基础上打开父子/兄弟 small-to-big 扩展）。 */
  private enum Tier {
    VECTOR("vector"),
    HYBRID("hybrid"),
    HYBRID_RERANK("hybrid+rerank"),
    HYBRID_RERANK_EXPAND("hybrid+rerank+expand");

    final String label;

    Tier(String label) {
      this.label = label;
    }
  }

  @Test
  @DisplayName("四档检索策略在固定语料上的召回/排序对比并产出报告")
  void evaluateRetrievalTiers() throws Exception {
    Map<String, Long> sourceToKb = sourceToKb();
    List<Long> scope = sourceToKb.values().stream().filter(id -> id > 0).distinct().sorted().toList();
    Assumptions.assumeTrue(!scope.isEmpty(),
        "未配置任何评测知识库 id（RAGEVAL_KB_REDIS/MYSQL/DISTRIBUTED），跳过检索评测");

    List<EvalQuestion> all = loadDataset();
    List<EvalQuestion> questions = all.stream()
        .filter(q -> sourceToKb.getOrDefault(q.source(), 0L) > 0)
        .toList();
    int skipped = all.size() - questions.size();
    Assumptions.assumeTrue(!questions.isEmpty(), "数据集中没有可评测题（对应知识库均未配置），跳过");

    List<String> variants = queryVariants();
    log.info("[rag-eval] 语料范围 kbIds={}, 评测题 {}（跳过 {}）, 查询变体={}, evalTopK={}, candidateTopK={}",
        scope, questions.size(), skipped, variants, evalTopK, candidateTopK);

    // 结果聚合：variant -> tier -> 各题得分
    Map<String, Map<Tier, List<QuestionScore>>> agg = new LinkedHashMap<>();
    for (String variant : variants) {
      Map<Tier, List<QuestionScore>> perTier = new LinkedHashMap<>();
      for (Tier tier : Tier.values()) {
        perTier.put(tier, new ArrayList<>());
      }
      for (EvalQuestion q : questions) {
        String queryText = "hard".equals(variant) ? q.queryHard() : q.question();
        if (queryText == null || queryText.isBlank()) {
          continue;
        }
        long expectedKb = sourceToKb.getOrDefault(q.source(), 0L);
        List<TextSegment> candidates = retrieveHybridCandidates(scope, queryText);
        List<TextSegment> vectorTopK = trim(retrieveVectorCandidates(scope, queryText), evalTopK);
        List<TextSegment> hybridTopK = trim(candidates, evalTopK);
        List<TextSegment> rerankTopK = trim(rerank(candidates, queryText), evalTopK);
        List<TextSegment> expandTopK = expand(rerankTopK);
        perTier.get(Tier.VECTOR).add(score(q, expectedKb, vectorTopK));
        perTier.get(Tier.HYBRID).add(score(q, expectedKb, hybridTopK));
        perTier.get(Tier.HYBRID_RERANK).add(score(q, expectedKb, rerankTopK));
        perTier.get(Tier.HYBRID_RERANK_EXPAND).add(score(q, expectedKb, expandTopK));
      }
      agg.put(variant, perTier);
    }

    String report = buildReport(agg, questions, scope, variants, skipped);
    Path written = writeReport(report);
    log.info("[rag-eval] 报告已写入: {}", written);
    System.out.println(report);

    // CI 门禁钩子：设置 RAGEVAL_MIN_COVERAGE 后，最优档关键点覆盖率低于阈值即失败
    String minCoverageEnv = System.getenv("RAGEVAL_MIN_COVERAGE");
    if (minCoverageEnv != null && !minCoverageEnv.isBlank()) {
      double threshold = Double.parseDouble(minCoverageEnv.trim());
      double best = agg.values().stream()
          .flatMap(m -> m.values().stream())
          .mapToDouble(scores -> avg(scores, QuestionScore::coverage))
          .max().orElse(0.0);
      if (best < threshold) {
        throw new AssertionError(String.format(Locale.ROOT,
            "关键点覆盖率门禁未通过：最优档 %.4f < 阈值 %.4f", best, threshold));
      }
    }
  }

  // ==================== 检索三档 ====================

  private List<TextSegment> retrieveVectorCandidates(List<Long> scope, String query) {
    return retrieve(scope, query, "vector");
  }

  private List<TextSegment> retrieveHybridCandidates(List<Long> scope, String query) {
    return retrieve(scope, query, null);
  }

  private List<TextSegment> retrieve(List<Long> scope, String query, String forcedSearchMode) {
    InterviewElasticsearchContentRetriever retriever = new InterviewElasticsearchContentRetriever(
        embeddingStore, llmProviderRegistry.getDefaultEmbeddingModel(), Math.max(candidateTopK, 1), 0.0,
        scope, queryProperties.getHybrid(), null, null,
        restClient, elasticSearchProperties.getIndexName(), objectMapper,
        forcedSearchMode, null, null, elasticSearchProperties.getDimensions());
    return retriever.retrieve(Query.from(query)).stream().map(Content::textSegment).toList();
  }

  /** 第四档只扩展已经完成 rerank 和 TopK 截断的子块，与生产链路保持一致。 */
  private List<TextSegment> expand(List<TextSegment> rerankedTopK) {
    List<Content> contents = rerankedTopK.stream().map(Content::from).toList();
    return new ContextExpansionService(segmentService, queryProperties.getParentExpand())
        .expand(contents).stream().map(Content::textSegment).toList();
  }

  private List<TextSegment> rerank(List<TextSegment> candidates, String query) {
    if (candidates.isEmpty() || !rerankService.isEnabled()) {
      return candidates;
    }
    Response<List<Double>> scored = rerankService.scoreAll(candidates, query);
    List<Double> scores = scored.content();
    if (scores == null || scores.size() != candidates.size()) {
      return candidates;
    }
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) {
      order.add(i);
    }
    order.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
    return order.stream().map(candidates::get).toList();
  }

  private List<TextSegment> trim(List<TextSegment> segments, int k) {
    return segments.size() <= k ? segments : segments.subList(0, k);
  }

  // ==================== 评分 ====================

  private QuestionScore score(EvalQuestion q, long expectedKb, List<TextSegment> topK) {
    int totalGroups = q.keyPoints().size();
    int firstHitRank = 0;
    boolean sourceHit = false;
    double dcg = 0.0;
    TreeSet<Integer> coveredGroups = new TreeSet<>();
    for (int i = 0; i < topK.size(); i++) {
      TextSegment seg = topK.get(i);
      String text = seg.text() == null ? "" : seg.text().toLowerCase(Locale.ROOT);
      if (expectedKb > 0 && String.valueOf(expectedKb)
          .equals(seg.metadata().getString(MetadataKeyConstant.DOC_ID))) {
        sourceHit = true;
      }
      int newlyCovered = 0;
      for (int g = 0; g < q.keyPoints().size(); g++) {
        if (coveredGroups.contains(g)) {
          continue;
        }
        if (groupMatches(q.keyPoints().get(g), text)) {
          coveredGroups.add(g);
          newlyCovered++;
        }
      }
      if (newlyCovered > 0) {
        if (firstHitRank == 0) {
          firstHitRank = i + 1;
        }
        // binary gain：该位置是否带来新关键点（不按组数累加，否则一格覆盖多组时
        // DCG 会超过按「每位置增益=1」构造的 IDCG，导致 NDCG > 1）
        dcg += 1.0 / log2(i + 2.0);
      }
    }
    double idcg = 0.0;
    int idealHits = Math.min(evalTopK, Math.max(1, totalGroups));
    for (int i = 1; i <= idealHits; i++) {
      idcg += 1.0 / log2(i + 1.0);
    }
    double coverage = totalGroups == 0 ? 0.0 : coveredGroups.size() * 1.0 / totalGroups;
    double reciprocalRank = firstHitRank == 0 ? 0.0 : 1.0 / firstHitRank;
    double ndcg = idcg == 0 ? 0.0 : dcg / idcg;
    return new QuestionScore(coverage, firstHitRank > 0, sourceHit, reciprocalRank, ndcg,
        q.source(), q.difficulty());
  }

  private boolean groupMatches(List<String> synonyms, String lowerText) {
    return synonyms.stream()
        .filter(s -> s != null && !s.isBlank())
        .map(s -> s.toLowerCase(Locale.ROOT))
        .anyMatch(lowerText::contains);
  }

  private double log2(double v) {
    return Math.log(v) / Math.log(2);
  }

  // ==================== 报告 ====================

  private String buildReport(Map<String, Map<Tier, List<QuestionScore>>> agg,
                             List<EvalQuestion> questions, List<Long> scope,
                             List<String> variants, int skipped) {
    StringBuilder sb = new StringBuilder();
    sb.append("# RAG 检索评测报告\n\n");
    sb.append("- 生成时间：").append(LocalDateTime.now().format(TS)).append("\n");
    sb.append("- 语料知识库 id：").append(scope).append("\n");
    sb.append("- 评测题量：").append(questions.size()).append("（跳过未配置知识库的题 ")
        .append(skipped).append("）\n");
    sb.append("- 评分 topK：").append(evalTopK).append("，候选 topK：").append(candidateTopK).append("\n");
    sb.append("- rerank 生效：").append(rerankService.isEnabled())
        .append("（provider=").append(rerankService.getEffectiveProvider()).append("）\n");
    sb.append("- 判定口径：关键点同义词组命中、无最低分阈值；前三档关闭父子扩展只比检索策略，")
        .append("第四档（+expand）在 hybrid+rerank 上叠加 small-to-big 上下文扩展\n\n");

    for (String variant : variants) {
      Map<Tier, List<QuestionScore>> perTier = agg.get(variant);
      sb.append("## 查询变体：").append("hard".equals(variant) ? "困难（query_hard，口语化）" : "标准（question）")
          .append("\n\n");
      sb.append("| 策略 | 关键点覆盖率 | 关键点命中率 | 来源命中率 | MRR | NDCG@").append(evalTopK).append(" |\n");
      sb.append("|------|------|------|------|------|------|\n");
      for (Tier tier : Tier.values()) {
        List<QuestionScore> scores = perTier.get(tier);
        sb.append("| ").append(tier.label)
            .append(" | ").append(pct(avg(scores, QuestionScore::coverage)))
            .append(" | ").append(pct(rate(scores, QuestionScore::keypointHit)))
            .append(" | ").append(pct(rate(scores, QuestionScore::sourceHit)))
            .append(" | ").append(fmt(avg(scores, QuestionScore::reciprocalRank)))
            .append(" | ").append(fmt(avg(scores, QuestionScore::ndcg)))
            .append(" |\n");
      }
      sb.append("\n");

      sb.append("按难度分组（关键点覆盖率）：\n\n");
      sb.append("| 策略 | fact | concept | synthesis |\n|------|------|------|------|\n");
      for (Tier tier : Tier.values()) {
        List<QuestionScore> scores = perTier.get(tier);
        sb.append("| ").append(tier.label)
            .append(" | ").append(pct(avgWhere(scores, "fact")))
            .append(" | ").append(pct(avgWhere(scores, "concept")))
            .append(" | ").append(pct(avgWhere(scores, "synthesis")))
            .append(" |\n");
      }
      sb.append("\n");

      sb.append("按知识库来源分组（关键点覆盖率）：\n\n");
      sb.append("| 策略 | redis | mysql | distributed | jvm | spring |\n")
          .append("|------|------|------|------|------|------|\n");
      for (Tier tier : Tier.values()) {
        List<QuestionScore> scores = perTier.get(tier);
        sb.append("| ").append(tier.label)
            .append(" | ").append(pct(avgWhereSource(scores, "redis")))
            .append(" | ").append(pct(avgWhereSource(scores, "mysql")))
            .append(" | ").append(pct(avgWhereSource(scores, "distributed")))
            .append(" | ").append(pct(avgWhereSource(scores, "jvm")))
            .append(" | ").append(pct(avgWhereSource(scores, "spring")))
            .append(" |\n");
      }
      sb.append("\n");
    }

    sb.append("> 说明：绝对数值依赖入库语料与 embedding/rerank 模型；结论看「各档相对增益」。\n");
    sb.append("> 写入简历前请保留本报告与评测运行参数，保证数字可追溯到一次完整运行。\n");
    return sb.toString();
  }

  private Path writeReport(String report) throws Exception {
    Path base = Paths.get("").toAbsolutePath();
    if (base.getFileName() != null && "backend".equals(base.getFileName().toString())) {
      base = base.getParent();
    }
    Path target = base.resolve(reportPath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, report, StandardCharsets.UTF_8);
    return target;
  }

  // ==================== 数据集加载 ====================

  @SuppressWarnings("unchecked")
  private List<EvalQuestion> loadDataset() throws Exception {
    String resourcePath = datasetPath.startsWith("classpath:")
        ? datasetPath.substring("classpath:".length())
        : "rag-eval/eval-dataset.yaml";
    try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
      Map<String, Object> root = new Yaml().load(in);
      List<Map<String, Object>> raw = (List<Map<String, Object>>) root.get("questions");
      List<EvalQuestion> parsed = new ArrayList<>();
      for (Map<String, Object> item : raw) {
        List<List<String>> keyPoints = new ArrayList<>();
        Object kp = item.get("key_points");
        if (kp instanceof List<?> groups) {
          for (Object group : groups) {
            if (group instanceof List<?> synonyms) {
              List<String> syn = new ArrayList<>();
              for (Object s : synonyms) {
                if (s != null) {
                  syn.add(String.valueOf(s));
                }
              }
              keyPoints.add(syn);
            } else if (group != null) {
              keyPoints.add(List.of(String.valueOf(group)));
            }
          }
        }
        parsed.add(new EvalQuestion(
            str(item.get("id")), str(item.get("source")), str(item.get("difficulty")),
            str(item.get("question")), str(item.get("query_hard")), keyPoints));
      }
      return parsed;
    }
  }

  private Map<String, Long> sourceToKb() {
    Map<String, Long> map = new LinkedHashMap<>();
    map.put("redis", kbRedis);
    map.put("mysql", kbMysql);
    map.put("distributed", kbDistributed);
    map.put("jvm", kbJvm);
    map.put("spring", kbSpring);
    return map;
  }

  private List<String> queryVariants() {
    String mode = Optional.ofNullable(System.getenv("RAGEVAL_QUERY_MODE")).orElse("both").trim();
    return switch (mode.toLowerCase(Locale.ROOT)) {
      case "easy", "question" -> List.of("easy");
      case "hard" -> List.of("hard");
      default -> List.of("easy", "hard");
    };
  }

  // ==================== 聚合工具 ====================

  private double avg(List<QuestionScore> scores, java.util.function.ToDoubleFunction<QuestionScore> f) {
    return scores.isEmpty() ? 0.0 : scores.stream().mapToDouble(f).average().orElse(0.0);
  }

  private double rate(List<QuestionScore> scores, java.util.function.Predicate<QuestionScore> p) {
    return scores.isEmpty() ? 0.0 : scores.stream().filter(p).count() * 1.0 / scores.size();
  }

  private double avgWhere(List<QuestionScore> scores, String difficulty) {
    List<QuestionScore> filtered = scores.stream()
        .filter(s -> Objects.equals(s.difficulty(), difficulty)).toList();
    return avg(filtered, QuestionScore::coverage);
  }

  private double avgWhereSource(List<QuestionScore> scores, String source) {
    List<QuestionScore> filtered = scores.stream()
        .filter(s -> Objects.equals(s.source(), source)).toList();
    return avg(filtered, QuestionScore::coverage);
  }

  private String pct(double v) {
    return String.format(Locale.ROOT, "%.1f%%", v * 100);
  }

  private String fmt(double v) {
    return String.format(Locale.ROOT, "%.4f", v);
  }

  private String str(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  // ==================== 数据载体 ====================

  private record EvalQuestion(String id, String source, String difficulty, String question,
                              String queryHard, List<List<String>> keyPoints) {}

  private record QuestionScore(double coverage, boolean keypointHit, boolean sourceHit,
                               double reciprocalRank, double ndcg, String source, String difficulty) {}
}
