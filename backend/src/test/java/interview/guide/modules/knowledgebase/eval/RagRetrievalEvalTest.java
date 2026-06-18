package interview.guide.modules.knowledgebase.eval;

import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.RerankService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RAG 检索侧评测（第二轮）。
 *
 * <p>第一轮用宽松的「关键点覆盖率」，三档策略都接近天花板（92% 左右）、看不出差异。第二轮做三点改造，
 * 让评测对「排序质量」和「漏召回」敏感，从而真实体现混合检索与 rerank 的增益：
 * <ol>
 *   <li><b>对排序敏感的指标</b>：Hit@1 / Hit@3 / MRR / NDCG@K，而非只看 topK 是否命中。</li>
 *   <li><b>per-chunk 分级相关性</b>：每个 chunk 按它覆盖了多少组 key_point 打分（覆盖组数即 gain），
 *       NDCG 据此衡量「高相关 chunk 是否排在前面」——这正是 rerank 优化的目标。</li>
 *   <li><b>简单 vs 困难两组查询</b>：question（含原文关键词）与 query_hard（口语化、避开术语）各跑一遍。
 *       困难查询逼出纯向量的漏召回，给混合/rerank 留出发挥空间。</li>
 * </ol>
 *
 * <p>候选集拉大到 {@code candidateTopK}（默认 20），rerank 从更大候选里精排取 {@code evalTopK}（默认 6），
 * 而非第一轮的 6 选 6，才能体现精排价值。
 *
 * <p>连真实 pgvector + DashScope，通过 {@code @Tag("rag-eval")} 隔离，默认不随普通 mvn test 运行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("rageval")
@Tag("rag-eval")
@DisplayName("RAG 检索侧评测（第二轮）：排序敏感指标 + 简单/困难查询对比")
class RagRetrievalEvalTest {

  private static final Logger log = LoggerFactory.getLogger(RagRetrievalEvalTest.class);

  @Autowired
  private KnowledgeBaseVectorService vectorService;

  @Autowired
  private KnowledgeBaseQueryService queryService;

  @Autowired
  private RerankService rerankService;

  @Autowired
  private ResourceLoader resourceLoader;

  @Value("${ragdataset.dataset-path}")
  private String datasetPath;

  @Value("${ragdataset.kb-redis}")
  private Long kbRedis;

  @Value("${ragdataset.kb-mysql}")
  private Long kbMysql;

  @Value("${ragdataset.kb-distributed}")
  private Long kbDistributed;

  @Value("${ragdataset.top-k:6}")
  private int evalTopK;

  @Value("${ragdataset.candidate-top-k:20}")
  private int candidateTopK;

  @Value("${ragdataset.report-path:target/rag-eval-report.md}")
  private String reportPath;

  @Value("${ragdataset.answer-eval-enabled:true}")
  private boolean answerEvalEnabled;

  @Test
  @DisplayName("跑全量评测集（简单+困难查询）并输出三档排序指标对比报告")
  void runRetrievalEvaluation() throws Exception {
    List<EvalQuestion> questions = loadDataset();
    Map<String, Long> sourceToKb = Map.of(
        "redis", kbRedis,
        "mysql", kbMysql,
        "distributed", kbDistributed);
    List<Long> allKbIds = List.of(kbRedis, kbMysql, kbDistributed);

    log.info("[rag-eval] 载入评测集 {} 题，evalTopK={}，candidateTopK={}", questions.size(), evalTopK, candidateTopK);

    // 简单查询组
    QuerySetResult easy = evaluateQuerySet(questions, allKbIds, sourceToKb, false);
    // 困难查询组
    QuerySetResult hard = evaluateQuerySet(questions, allKbIds, sourceToKb, true);
    // P3 多路融合 + HyDE 对比只在困难查询组跑（简单查询已近天花板，无区分度）
    FusionSetResult fusionHard = evaluateFusionSet(questions, allKbIds, true);
    AnswerEvalResult answerEval = answerEvalEnabled
        ? evaluateAnswers(questions, allKbIds, true)
        : AnswerEvalResult.disabled();

    String report = buildReport(questions.size(), easy, hard, fusionHard, answerEval);
    writeReport(report);
    log.info("\n{}", report);
  }

  private QuerySetResult evaluateQuerySet(List<EvalQuestion> questions, List<Long> allKbIds,
                                          Map<String, Long> sourceToKb, boolean useHard) {
    StrategyAccumulator pureVector = new StrategyAccumulator("纯向量");
    StrategyAccumulator hybrid = new StrategyAccumulator("混合检索");
    StrategyAccumulator hybridRerank = new StrategyAccumulator("混合+rerank");

    for (EvalQuestion q : questions) {
      String query = useHard ? q.queryHard : q.question;
      if (query == null || query.isBlank()) {
        query = q.question;
      }

      // 纯向量：取 candidateTopK 候选后截断到 evalTopK 评分
      List<Document> vectorHits = cap(
          vectorService.similaritySearch(query, allKbIds, candidateTopK, 0.0), evalTopK);
      // 混合检索：RRF 融合产出候选，截断到 evalTopK 评分
      List<Document> hybridCandidates = vectorService.hybridSearch(query, allKbIds, candidateTopK, 0.0);
      List<Document> hybridHits = cap(hybridCandidates, evalTopK);
      // 混合 + rerank：从更大的混合候选里精排，再截断到 evalTopK 评分
      List<Document> rerankHits = cap(rerankService.rerank(query, hybridCandidates), evalTopK);

      pureVector.add(q, score(q, vectorHits));
      hybrid.add(q, score(q, hybridHits));
      hybridRerank.add(q, score(q, rerankHits));
    }
    return new QuerySetResult(pureVector, hybrid, hybridRerank);
  }

  /**
   * P3 多路融合 + HyDE 对比：用反射切换 queryService 的 fusion / hyde 开关，复用其真实检索链路
   * （含 rewrite、HyDE、多路 RRF 融合、rerank），与基线「混合+rerank」对比。仅在困难查询组跑，
   * 结束时恢复原开关，避免污染后续档位。
   */
  private FusionSetResult evaluateFusionSet(List<EvalQuestion> questions, List<Long> allKbIds,
                                            boolean useHard) {
    boolean origFusion = (boolean) ReflectionTestUtils.getField(queryService, "fusionEnabled");
    boolean origHyde = (boolean) ReflectionTestUtils.getField(queryService, "hydeEnabled");
    StrategyAccumulator fusionRerank = new StrategyAccumulator("融合+rerank");
    StrategyAccumulator fusionHydeRerank = new StrategyAccumulator("融合+HyDE+rerank");
    try {
      ReflectionTestUtils.setField(queryService, "fusionEnabled", true);
      // 融合 + rerank（HyDE 关）：原句 / rewrite 两路 RRF 融合
      ReflectionTestUtils.setField(queryService, "hydeEnabled", false);
      for (EvalQuestion q : questions) {
        List<Document> hits = cap(queryService.retrieveForEvaluation(allKbIds, hardQuery(q, useHard)), evalTopK);
        fusionRerank.add(q, score(q, hits));
      }
      // 融合 + HyDE + rerank：再加一路 HyDE 假设文档
      ReflectionTestUtils.setField(queryService, "hydeEnabled", true);
      for (EvalQuestion q : questions) {
        List<Document> hits = cap(queryService.retrieveForEvaluation(allKbIds, hardQuery(q, useHard)), evalTopK);
        fusionHydeRerank.add(q, score(q, hits));
      }
    } finally {
      ReflectionTestUtils.setField(queryService, "fusionEnabled", origFusion);
      ReflectionTestUtils.setField(queryService, "hydeEnabled", origHyde);
    }
    return new FusionSetResult(fusionRerank, fusionHydeRerank);
  }

  private String hardQuery(EvalQuestion q, boolean useHard) {
    if (useHard && q.queryHard != null && !q.queryHard.isBlank()) {
      return q.queryHard;
    }
    return q.question;
  }

  private AnswerEvalResult evaluateAnswers(List<EvalQuestion> questions, List<Long> allKbIds,
                                           boolean useHard) {
    AnswerEvalResult result = new AnswerEvalResult(true);
    for (EvalQuestion q : questions) {
      String query = useHard && q.queryHard != null && !q.queryHard.isBlank()
          ? q.queryHard
          : q.question;
      List<Document> contexts = cap(
          rerankService.rerank(query, vectorService.hybridSearch(query, allKbIds, candidateTopK, 0.0)),
          evalTopK
      );
      String contextText = contexts.stream()
          .map(Document::getText)
          .filter(text -> text != null && !text.isBlank())
          .reduce("", (left, right) -> left + "\n" + right);
      try {
        String answer = queryService.answerQuestionForEvaluation(allKbIds, query);
        AnswerScore score = scoreAnswer(q, answer, contextText);
        result.add(new AnswerCase(q.id, query, answer, score));
      } catch (Exception e) {
        result.add(new AnswerCase(
            q.id,
            query,
            "【生成失败】" + e.getMessage(),
            new AnswerScore(0.0, 0.0, false)
        ));
      }
    }
    return result;
  }

  // ==================== 评分 ====================

  /**
   * 单条 chunk 的分级相关性：它覆盖了该题多少组 key_point，就得多少分（gain）。
   * 0 表示与该题完全不相关。
   */
  private int chunkRelevance(EvalQuestion q, String chunkText) {
    if (chunkText == null || chunkText.isBlank()) {
      return 0;
    }
    String lower = chunkText.toLowerCase(Locale.ROOT);
    int grade = 0;
    for (List<String> synonyms : q.keyPoints) {
      boolean hit = synonyms.stream().anyMatch(s -> lower.contains(s.toLowerCase(Locale.ROOT)));
      if (hit) {
        grade++;
      }
    }
    return grade;
  }

  private QuestionScore score(EvalQuestion q, List<Document> hits) {
    int n = hits.size();
    int[] rel = new int[n];
    for (int i = 0; i < n; i++) {
      rel[i] = chunkRelevance(q, hits.get(i).getText());
    }

    // Hit@1 / Hit@3：前 1 / 前 3 内是否有相关 chunk（relevance > 0）
    boolean hit1 = n >= 1 && rel[0] > 0;
    boolean hit3 = false;
    for (int i = 0; i < Math.min(3, n); i++) {
      if (rel[i] > 0) {
        hit3 = true;
        break;
      }
    }

    // MRR：第一个相关 chunk 的倒数排名
    double rr = 0.0;
    for (int i = 0; i < n; i++) {
      if (rel[i] > 0) {
        rr = 1.0 / (i + 1);
        break;
      }
    }

    // NDCG@K：分级增益按位置折扣，再除以理想排序的 DCG
    double ndcg = computeNdcg(q, rel);

    return new QuestionScore(hit1, hit3, rr, ndcg);
  }

  private double computeNdcg(EvalQuestion q, int[] rel) {
    double dcg = 0.0;
    for (int i = 0; i < rel.length; i++) {
      dcg += (Math.pow(2, rel[i]) - 1) / (Math.log(i + 2) / Math.log(2));
    }
    // 理想 DCG：该题所有 key_point 组都能命中的「满分 chunk」排在第 1 位即为理想上界。
    // 这里用「单个满分 chunk（gain = key_point 组数）排在首位」作为 IDCG 归一基准。
    int maxGrade = q.keyPoints.size();
    double idcg = (Math.pow(2, maxGrade) - 1) / (Math.log(2) / Math.log(2));
    if (idcg <= 0) {
      return 0.0;
    }
    return Math.min(1.0, dcg / idcg);
  }

  private List<Document> cap(List<Document> docs, int topK) {
    if (docs == null) {
      return List.of();
    }
    return docs.size() <= topK ? docs : new ArrayList<>(docs.subList(0, topK));
  }

  private AnswerScore scoreAnswer(EvalQuestion q, String answer, String contextText) {
    if (answer == null || answer.isBlank()) {
      return new AnswerScore(0.0, 0.0, true);
    }
    String answerLower = answer.toLowerCase(Locale.ROOT);
    String contextLower = contextText == null ? "" : contextText.toLowerCase(Locale.ROOT);
    int answerCovered = 0;
    int faithfulCovered = 0;
    for (List<String> synonyms : q.keyPoints) {
      boolean inAnswer = synonyms.stream()
          .anyMatch(s -> answerLower.contains(s.toLowerCase(Locale.ROOT)));
      if (!inAnswer) {
        continue;
      }
      answerCovered++;
      boolean inContext = synonyms.stream()
          .anyMatch(s -> contextLower.contains(s.toLowerCase(Locale.ROOT)));
      if (inContext) {
        faithfulCovered++;
      }
    }
    double answerCoverage = q.keyPoints.isEmpty() ? 0.0 : (double) answerCovered / q.keyPoints.size();
    double faithfulness = answerCovered == 0 ? 0.0 : (double) faithfulCovered / answerCovered;
    return new AnswerScore(answerCoverage, faithfulness, isNoResult(answer));
  }

  private boolean isNoResult(String answer) {
    String text = answer == null ? "" : answer;
    return text.contains("未检索到相关信息")
        || text.contains("没有找到足够信息")
        || text.contains("无法根据提供内容回答")
        || text.contains("信息不足");
  }

  // ==================== 报告 ====================

  private String buildReport(int total, QuerySetResult easy, QuerySetResult hard,
                             FusionSetResult fusionHard, AnswerEvalResult answerEval) {
    StringBuilder sb = new StringBuilder();
    sb.append("# RAG 检索侧评测报告（第二轮）\n\n");
    sb.append("- 评测集规模：").append(total).append(" 题\n");
    sb.append("- 评分 topK：").append(evalTopK).append("，候选 topK：").append(candidateTopK).append("\n");
    sb.append("- 语料知识库：redis=").append(kbRedis)
        .append(", mysql=").append(kbMysql)
        .append(", distributed=").append(kbDistributed).append("\n\n");
    sb.append("指标说明：Hit@1/Hit@3 = 前 1/前 3 命中相关 chunk 的题目比例；")
        .append("MRR = 首个相关结果的平均倒数排名；NDCG@").append(evalTopK)
        .append(" = 分级相关性的排序质量。\n\n");

    appendQuerySetTable(sb, "## 简单查询（含原文关键词）", easy, total);
    appendQuerySetTable(sb, "## 困难查询（口语化、避开术语）", hard, total);
    appendFusionTable(sb, "## P3 多路融合 + HyDE 对比（困难查询）", hard.hybridRerank, fusionHard, total);
    appendAnswerEval(sb, answerEval);

    sb.append("## 解读\n\n");
    sb.append("- 简单查询下三档接近，是因为查询词与原文高度重合，纯向量已足够；\n");
    sb.append("- 困难查询更能体现混合检索（关键词通道兜底）与 rerank（精排）的增益，");
    sb.append("对比同组内三行的 MRR / NDCG 变化即为各策略的相对提升；\n");
    sb.append("- P3 融合（原句 / rewrite / HyDE 多路 RRF）相对单路「混合+rerank」的增益，");
    sb.append("看「P3 多路融合」表内三行 Hit@1 / MRR / NDCG 的变化。\n");
    return sb.toString();
  }

  private void appendAnswerEval(StringBuilder sb, AnswerEvalResult result) {
    sb.append("## 答案质量评测（困难查询）\n\n");
    if (!result.enabled()) {
      sb.append("- 未启用答案质量评测。\n\n");
      return;
    }
    sb.append("- 答案相关性：").append(pct(result.avgAnswerCoverage())).append("\n");
    sb.append("- 资料忠实度：").append(pct(result.avgFaithfulness())).append("\n");
    sb.append("- 拒答次数：").append(result.noResultCount()).append("\n\n");

    List<AnswerCase> failures = result.cases().stream()
        .sorted(Comparator.comparingDouble(c -> c.score().overall()))
        .limit(3)
        .toList();
    if (failures.isEmpty()) {
      return;
    }
    sb.append("### 低分样例\n\n");
    for (AnswerCase item : failures) {
      sb.append("- **").append(item.id()).append("**：")
          .append(item.query()).append("\n\n")
          .append("  - 相关性：").append(pct(item.score().answerCoverage()))
          .append("，忠实度：").append(pct(item.score().faithfulness()))
          .append("，是否拒答：").append(item.score().noResult() ? "是" : "否")
          .append("\n")
          .append("  - 回答摘要：").append(clip(item.answer(), 160)).append("\n\n");
    }
  }

  private String clip(String text, int maxChars) {
    if (text == null || text.isBlank()) {
      return "";
    }
    String normalized = text.replaceAll("\\s+", " ").trim();
    return normalized.length() <= maxChars
        ? normalized
        : normalized.substring(0, maxChars) + "...";
  }

  private void appendQuerySetTable(StringBuilder sb, String title, QuerySetResult r, int total) {
    sb.append(title).append("\n\n");
    sb.append("| 策略 | Hit@1 | Hit@3 | MRR | NDCG@").append(evalTopK).append(" |\n");
    sb.append("|------|-------|-------|-----|--------|\n");
    for (StrategyAccumulator s : List.of(r.pureVector, r.hybrid, r.hybridRerank)) {
      sb.append("| ").append(s.name)
          .append(" | ").append(pct(s.hit1Rate(total)))
          .append(" | ").append(pct(s.hit3Rate(total)))
          .append(" | ").append(num(s.avgMrr(total)))
          .append(" | ").append(num(s.avgNdcg(total)))
          .append(" |\n");
    }
    sb.append("\n");
  }

  private void appendFusionTable(StringBuilder sb, String title, StrategyAccumulator baseline,
                                 FusionSetResult r, int total) {
    sb.append(title).append("\n\n");
    sb.append("| 策略 | Hit@1 | Hit@3 | MRR | NDCG@").append(evalTopK).append(" |\n");
    sb.append("|------|-------|-------|-----|--------|\n");
    sb.append("| ").append(baseline.name).append("（基线）")
        .append(" | ").append(pct(baseline.hit1Rate(total)))
        .append(" | ").append(pct(baseline.hit3Rate(total)))
        .append(" | ").append(num(baseline.avgMrr(total)))
        .append(" | ").append(num(baseline.avgNdcg(total)))
        .append(" |\n");
    for (StrategyAccumulator s : List.of(r.fusionRerank, r.fusionHydeRerank)) {
      sb.append("| ").append(s.name)
          .append(" | ").append(pct(s.hit1Rate(total)))
          .append(" | ").append(pct(s.hit3Rate(total)))
          .append(" | ").append(num(s.avgMrr(total)))
          .append(" | ").append(num(s.avgNdcg(total)))
          .append(" |\n");
    }
    sb.append("\n");
  }

  private String pct(double v) {
    return String.format(Locale.ROOT, "%.1f%%", v * 100);
  }

  private String num(double v) {
    return String.format(Locale.ROOT, "%.3f", v);
  }

  private void writeReport(String report) throws Exception {
    Path path = Path.of(reportPath);
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      w.write(report);
    }
    log.info("[rag-eval] 报告已写入 {}", path.toAbsolutePath());
  }

  // ==================== 数据集加载 ====================

  @SuppressWarnings("unchecked")
  private List<EvalQuestion> loadDataset() throws Exception {
    try (InputStream in = resourceLoader.getResource(datasetPath).getInputStream()) {
      Map<String, Object> root = new Yaml().load(in);
      List<Map<String, Object>> raw = (List<Map<String, Object>>) root.get("questions");
      List<EvalQuestion> result = new ArrayList<>();
      for (Map<String, Object> item : raw) {
        EvalQuestion q = new EvalQuestion();
        q.id = String.valueOf(item.get("id"));
        q.source = String.valueOf(item.get("source"));
        q.difficulty = String.valueOf(item.get("difficulty"));
        q.question = String.valueOf(item.get("question"));
        Object hard = item.get("query_hard");
        q.queryHard = hard == null ? null : String.valueOf(hard);
        q.keyPoints = new ArrayList<>();
        for (Object group : (List<Object>) item.get("key_points")) {
          List<String> synonyms = new ArrayList<>();
          for (Object s : (List<Object>) group) {
            synonyms.add(String.valueOf(s));
          }
          q.keyPoints.add(synonyms);
        }
        result.add(q);
      }
      return result;
    }
  }

  // ==================== 内部数据结构 ====================

  private static class EvalQuestion {
    String id;
    String source;
    String difficulty;
    String question;
    String queryHard;
    List<List<String>> keyPoints;
  }

  private record QuestionScore(boolean hit1, boolean hit3, double rr, double ndcg) {
  }

  private record QuerySetResult(StrategyAccumulator pureVector,
                                StrategyAccumulator hybrid,
                                StrategyAccumulator hybridRerank) {
  }

  private record FusionSetResult(StrategyAccumulator fusionRerank,
                                 StrategyAccumulator fusionHydeRerank) {
  }

  private record AnswerScore(double answerCoverage, double faithfulness, boolean noResult) {
    double overall() {
      return (answerCoverage + faithfulness) / 2.0;
    }
  }

  private record AnswerCase(String id, String query, String answer, AnswerScore score) {
  }

  private static class AnswerEvalResult {
    private final boolean enabled;
    private final List<AnswerCase> cases = new ArrayList<>();

    AnswerEvalResult(boolean enabled) {
      this.enabled = enabled;
    }

    static AnswerEvalResult disabled() {
      return new AnswerEvalResult(false);
    }

    void add(AnswerCase answerCase) {
      cases.add(answerCase);
    }

    boolean enabled() {
      return enabled;
    }

    List<AnswerCase> cases() {
      return cases;
    }

    double avgAnswerCoverage() {
      return cases.stream()
          .mapToDouble(c -> c.score().answerCoverage())
          .average()
          .orElse(0.0);
    }

    double avgFaithfulness() {
      return cases.stream()
          .mapToDouble(c -> c.score().faithfulness())
          .average()
          .orElse(0.0);
    }

    long noResultCount() {
      return cases.stream().filter(c -> c.score().noResult()).count();
    }
  }

  private static class StrategyAccumulator {
    final String name;
    int hit1Count = 0;
    int hit3Count = 0;
    double mrrSum = 0.0;
    double ndcgSum = 0.0;

    StrategyAccumulator(String name) {
      this.name = name;
    }

    void add(EvalQuestion q, QuestionScore s) {
      if (s.hit1()) {
        hit1Count++;
      }
      if (s.hit3()) {
        hit3Count++;
      }
      mrrSum += s.rr();
      ndcgSum += s.ndcg();
    }

    double hit1Rate(int total) {
      return total == 0 ? 0.0 : (double) hit1Count / total;
    }

    double hit3Rate(int total) {
      return total == 0 ? 0.0 : (double) hit3Count / total;
    }

    double avgMrr(int total) {
      return total == 0 ? 0.0 : mrrSum / total;
    }

    double avgNdcg(int total) {
      return total == 0 ? 0.0 : ndcgSum / total;
    }
  }
}
