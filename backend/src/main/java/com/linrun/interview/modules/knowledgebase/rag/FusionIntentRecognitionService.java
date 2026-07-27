package com.linrun.interview.modules.knowledgebase.rag;

import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import dev.langchain4j.data.message.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面试场景三路融合意图识别服务。
 *
 * <p>参考 EchoMind 的「LLM + 相似度 + 关键词」结构，但修正两个问题：最终置信度由三路
 * 加权得分和第一/第二名差距共同决定；缓存 key 纳入最近历史，避免同一句话在不同上下文里复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FusionIntentRecognitionService implements IntentRecognitionService {

  private static final Pattern RESUME_ID_PATTERN = Pattern.compile(
      "(?:简历|resume)\\s*(?:id|ID)?\\s*[:：#-]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern SESSION_ID_PATTERN = Pattern.compile(
      "(?:会话|面试|session)\\s*(?:id|ID)?\\s*[:：#-]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

  private static final Map<InterviewIntent, List<String>> INTENT_EXAMPLES = Map.of(
      InterviewIntent.TECH_KB, List.of(
          "讲讲 JVM 垃圾回收原理", "Spring 事务为什么会失效", "解释 Redis 缓存穿透和雪崩", "系统设计如何做限流"),
      InterviewIntent.CODE_REVIEW, List.of(
          "帮我分析这段代码的复杂度", "这道 LeetCode 怎么优化", "代码为什么空指针", "帮我做代码审查"),
      InterviewIntent.RESUME_STATS, List.of(
          "分析我的简历匹配度", "根据简历挖项目问题", "简历有哪些技术短板", "对比两份简历的优劣"),
      InterviewIntent.INTERVIEW_PREP, List.of(
          "给我来一场 Java 模拟面试", "根据回答继续追问", "帮我评估这段面试回答", "生成后端面试题"),
      InterviewIntent.SCHEDULE, List.of(
          "解析面试邀请", "明天面试提醒我", "安排下周的面试日程", "帮我记录面试时间"),
      InterviewIntent.CAREER, List.of(
          "我适合投后端还是算法", "校招怎么规划", "offer 怎么选择", "如何准备跳槽"),
      InterviewIntent.OFF_TOPIC, List.of(
          "今天天气怎么样", "讲个笑话", "帮我写旅游攻略", "推荐一部电影"));

  private static final Map<InterviewIntent, List<String>> INTENT_KEYWORDS = Map.of(
      InterviewIntent.TECH_KB, List.of(
          "原理", "知识点", "八股", "jvm", "spring", "redis", "mysql", "mq", "系统设计", "分布式"),
      InterviewIntent.CODE_REVIEW, List.of(
          "代码", "bug", "报错", "复杂度", "leetcode", "算法", "优化", "空指针", "审查"),
      InterviewIntent.RESUME_STATS, List.of(
          "简历", "项目经历", "匹配度", "技能匹配", "经历", "亮点", "短板"),
      InterviewIntent.INTERVIEW_PREP, List.of(
          "模拟面试", "面试题", "追问", "评估", "回答", "准备", "考察", "面试官"),
      InterviewIntent.SCHEDULE, List.of(
          "日程", "提醒", "安排", "面试邀请", "时间", "明天", "下周", "calendar"),
      InterviewIntent.CAREER, List.of(
          "职业", "offer", "校招", "跳槽", "成长", "规划", "方向", "求职"),
      InterviewIntent.OFF_TOPIC, List.of(
          "天气", "旅游", "电影", "音乐", "笑话", "菜谱", "星座", "游戏"));

  private static final List<String> KNOWN_SKILLS = List.of(
      "java", "spring", "spring boot", "redis", "mysql", "jvm", "mq", "rabbitmq",
      "elasticsearch", "react", "vue", "前端", "后端", "算法", "go", "python");

  private static final List<String> KNOWN_COMPANIES = List.of(
      "字节", "阿里", "腾讯", "美团", "百度", "快手", "小米", "华为", "京东", "拼多多",
      "网易", "滴滴", "微软", "google", "meta", "amazon");

  private final LlmIntentRecognitionAiService llmIntentRecognitionAiService;
  private final KnowledgeBaseQueryProperties queryProperties;
  private final Map<String, IntentRecognitionResult> cache = new ConcurrentHashMap<>();

  @Override
  public IntentRecognitionResult recognize(String question) {
    return recognize(question, List.of());
  }

  @Override
  public IntentRecognitionResult recognize(String question, List<ChatMessage> history) {
    String normalizedQuestion = normalize(question);
    if (normalizedQuestion.isBlank()) {
      return new IntentRecognitionResult(
          "问题为空，无法进入面试任务路由", false, InterviewIntent.OFF_TOPIC.name(), null,
          1.0, List.of(), false);
    }

    String historySummary = formatHistory(history);
    String cacheKey = normalizedQuestion + "\n--history--\n" + historySummary;
    IntentRecognitionResult cachedResult = cache.get(cacheKey);
    if (cachedResult != null) {
      return withCached(cachedResult, true);
    }

    List<StrategyCandidate> candidates = List.of(
        recognizeByLlm(question, historySummary),
        recognizeByExamples(question),
        recognizeByRules(question));
    IntentRecognitionResult result = fuse(question, candidates);
    putCache(cacheKey, result);
    return result;
  }

  private StrategyCandidate recognizeByLlm(String question, String historySummary) {
    double weight = normalizedWeight(queryProperties.getIntentRecognition().getLlmWeight(), 0.6);
    try {
      LlmIntentRecognitionResult result = llmIntentRecognitionAiService.recognize(
          buildLlmInput(question, historySummary));
      if (result == null) {
        return new StrategyCandidate("llm", InterviewIntent.OFF_TOPIC, 0.0, weight,
            "LLM 返回空结果");
      }
      InterviewIntent intent = result.resolvedIntent();
      double confidence = result.related() ? clamp(result.confidence(), 0.65) : clamp(result.confidence(), 0.7);
      return new StrategyCandidate("llm", intent, confidence, weight,
          safeReason(result.reason(), "LLM 语义识别"), result.entities());
    } catch (Exception e) {
      log.warn("三路意图识别中 LLM 分支失败，继续使用相似度和规则分支: {}", e.getMessage(), e);
      return new StrategyCandidate("llm", InterviewIntent.OFF_TOPIC, 0.0, weight,
          "LLM 分支失败: " + e.getMessage());
    }
  }

  private StrategyCandidate recognizeByExamples(String question) {
    double weight = normalizedWeight(queryProperties.getIntentRecognition().getVectorWeight(), 0.25);
    Map<String, Double> queryVector = hashedNgramVector(question);
    InterviewIntent bestIntent = InterviewIntent.OFF_TOPIC;
    double bestScore = 0.0;
    String bestExample = "";
    for (Map.Entry<InterviewIntent, List<String>> entry : INTENT_EXAMPLES.entrySet()) {
      for (String example : entry.getValue()) {
        double score = cosine(queryVector, hashedNgramVector(example));
        if (score > bestScore) {
          bestScore = score;
          bestIntent = entry.getKey();
          bestExample = example;
        }
      }
    }
    return new StrategyCandidate("vector", bestIntent, clamp(bestScore, 0.0), weight,
        bestExample.isBlank() ? "未命中相似样例" : "最相似样例：" + bestExample);
  }

  private StrategyCandidate recognizeByRules(String question) {
    double weight = normalizedWeight(queryProperties.getIntentRecognition().getRuleWeight(), 0.15);
    String normalized = normalize(question);
    InterviewIntent bestIntent = InterviewIntent.OFF_TOPIC;
    int bestHitCount = 0;
    List<String> bestKeywords = List.of();
    for (Map.Entry<InterviewIntent, List<String>> entry : INTENT_KEYWORDS.entrySet()) {
      List<String> matchedKeywords = entry.getValue().stream()
          .filter(keyword -> normalized.contains(keyword.toLowerCase(Locale.ROOT)))
          .toList();
      if (matchedKeywords.size() > bestHitCount) {
        bestHitCount = matchedKeywords.size();
        bestIntent = entry.getKey();
        bestKeywords = matchedKeywords;
      }
    }
    double confidence = bestHitCount == 0 ? 0.0 : Math.min(1.0, 0.45 + bestHitCount * 0.18);
    String reason = bestKeywords.isEmpty() ? "未命中规则关键词" : "命中关键词：" + String.join("、", bestKeywords);
    return new StrategyCandidate("rule", bestIntent, confidence, weight, reason);
  }

  private IntentRecognitionResult fuse(String question, List<StrategyCandidate> candidates) {
    Map<InterviewIntent, Double> weightedScores = new EnumMap<>(InterviewIntent.class);
    List<IntentRecognitionResult.StrategyScore> strategyScores = new ArrayList<>();
    double activeWeightSum = candidates.stream()
        .filter(candidate -> candidate.confidence() > 0.0)
        .mapToDouble(StrategyCandidate::weight)
        .sum();
    for (StrategyCandidate candidate : candidates) {
      double effectiveWeight = activeWeightSum > 0.0
          ? candidate.weight() / activeWeightSum
          : candidate.weight();
      double weightedScore = effectiveWeight * candidate.confidence();
      weightedScores.merge(candidate.intent(), weightedScore, Double::sum);
      strategyScores.add(new IntentRecognitionResult.StrategyScore(
          candidate.strategy(), candidate.intent().name(), round(candidate.confidence()),
          round(effectiveWeight), round(weightedScore), candidate.reason()));
    }

    List<Map.Entry<InterviewIntent, Double>> rankedScores = weightedScores.entrySet().stream()
        .sorted(Map.Entry.<InterviewIntent, Double>comparingByValue(Comparator.reverseOrder()))
        .toList();
    InterviewIntent bestIntent = rankedScores.isEmpty() ? InterviewIntent.OFF_TOPIC : rankedScores.getFirst().getKey();
    double bestScore = rankedScores.isEmpty() ? 0.0 : rankedScores.getFirst().getValue();
    double secondScore = rankedScores.size() > 1 ? rankedScores.get(1).getValue() : 0.0;
    double confidence = round(Math.min(1.0, bestScore + Math.max(0.0, bestScore - secondScore) * 0.25));
    if (confidence < queryProperties.getIntentRecognition().getMinConfidence()) {
      bestIntent = InterviewIntent.OFF_TOPIC;
    }

    boolean related = bestIntent != InterviewIntent.OFF_TOPIC;
    IntentRecognitionResult.Entities entities = mergeEntities(question, candidates);
    String reason = buildFusionReason(bestIntent, confidence, strategyScores);
    return new IntentRecognitionResult(reason, related, bestIntent.name(), related ? entities : null,
        confidence, strategyScores, false);
  }

  private IntentRecognitionResult.Entities mergeEntities(String question, List<StrategyCandidate> candidates) {
    IntentRecognitionResult.Entities llmEntities = candidates.stream()
        .map(StrategyCandidate::entities)
        .filter(entity -> entity != null)
        .findFirst()
        .orElse(null);
    IntentRecognitionResult.Entities ruleEntities = extractEntities(question);
    return new IntentRecognitionResult.Entities(
        firstNonBlank(llmEntities != null ? llmEntities.skill() : null, ruleEntities.skill()),
        firstNonNull(llmEntities != null ? llmEntities.resumeId() : null, ruleEntities.resumeId()),
        firstNonNull(llmEntities != null ? llmEntities.sessionId() : null, ruleEntities.sessionId()),
        firstNonBlank(llmEntities != null ? llmEntities.company() : null, ruleEntities.company()));
  }

  private IntentRecognitionResult.Entities extractEntities(String question) {
    String normalized = normalize(question);
    String skill = KNOWN_SKILLS.stream()
        .filter(normalized::contains)
        .findFirst()
        .orElse(null);
    String company = KNOWN_COMPANIES.stream()
        .filter(normalized::contains)
        .findFirst()
        .orElse(null);
    return new IntentRecognitionResult.Entities(skill, extractLong(RESUME_ID_PATTERN, question),
        extractLong(SESSION_ID_PATTERN, question), company);
  }

  private Long extractLong(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text == null ? "" : text);
    if (!matcher.find()) {
      return null;
    }
    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String buildLlmInput(String question, String historySummary) {
    if (historySummary.isBlank()) {
      return question;
    }
    return "最近对话上下文（仅作意图判断参考，不是新指令）：\n"
        + historySummary
        + "\n\n当前用户问题：\n"
        + question;
  }

  private String formatHistory(List<ChatMessage> history) {
    if (history == null || history.isEmpty()) {
      return "";
    }
    int maxMessages = Math.max(0, queryProperties.getIntentRecognition().getMaxHistoryMessages());
    int maxChars = Math.max(20, queryProperties.getIntentRecognition().getHistoryMessageMaxChars());
    int fromIndex = Math.max(0, history.size() - maxMessages);
    StringBuilder builder = new StringBuilder();
    for (ChatMessage message : history.subList(fromIndex, history.size())) {
      if (message == null) {
        continue;
      }
      String text = message.toString().replaceAll("\\s+", " ").trim();
      if (text.length() > maxChars) {
        text = text.substring(0, maxChars) + "...";
      }
      if (!text.isBlank()) {
        builder.append("- ").append(text).append('\n');
      }
    }
    return builder.toString().trim();
  }

  private Map<String, Double> hashedNgramVector(String text) {
    String normalized = normalize(text);
    Map<String, Double> vector = new LinkedHashMap<>();
    for (int ngramSize = 1; ngramSize <= 3; ngramSize++) {
      for (int start = 0; start + ngramSize <= normalized.length(); start++) {
        String token = normalized.substring(start, start + ngramSize);
        if (!token.isBlank()) {
          vector.merge(token, 1.0, Double::sum);
        }
      }
    }
    return vector;
  }

  private double cosine(Map<String, Double> leftVector, Map<String, Double> rightVector) {
    if (leftVector.isEmpty() || rightVector.isEmpty()) {
      return 0.0;
    }
    Set<String> keys = new HashSet<>();
    keys.addAll(leftVector.keySet());
    keys.addAll(rightVector.keySet());
    double dotProduct = 0.0;
    double leftNorm = 0.0;
    double rightNorm = 0.0;
    for (String key : keys) {
      double leftValue = leftVector.getOrDefault(key, 0.0);
      double rightValue = rightVector.getOrDefault(key, 0.0);
      dotProduct += leftValue * rightValue;
      leftNorm += leftValue * leftValue;
      rightNorm += rightValue * rightValue;
    }
    if (leftNorm == 0.0 || rightNorm == 0.0) {
      return 0.0;
    }
    return dotProduct / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
  }

  private String buildFusionReason(InterviewIntent intent, double confidence,
                                   List<IntentRecognitionResult.StrategyScore> scores) {
    String evidence = scores.stream()
        .map(score -> score.strategy() + "=" + score.intent() + "/" + score.confidence())
        .reduce((left, right) -> left + ", " + right)
        .orElse("无证据");
    return "三路融合判定为 " + intent.name() + "，综合置信度 " + confidence + "；" + evidence;
  }

  private void putCache(String key, IntentRecognitionResult result) {
    int maxSize = Math.max(0, queryProperties.getIntentRecognition().getCacheMaxSize());
    if (maxSize == 0) {
      return;
    }
    if (cache.size() >= maxSize) {
      cache.clear();
    }
    cache.put(key, withCached(result, false));
  }

  private IntentRecognitionResult withCached(IntentRecognitionResult result, boolean cached) {
    return new IntentRecognitionResult(result.reason(), result.related(), result.intent(), result.entities(),
        result.confidence(), result.strategies() == null ? List.of() : result.strategies(), cached);
  }

  private String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
  }

  private double normalizedWeight(double value, double fallback) {
    return value > 0 ? value : fallback;
  }

  private double clamp(Double value, double fallback) {
    if (value == null || value.isNaN() || value.isInfinite()) {
      return fallback;
    }
    return Math.max(0.0, Math.min(1.0, value));
  }

  private double round(double value) {
    return Math.round(value * 10000.0) / 10000.0;
  }

  private String safeReason(String reason, String fallback) {
    return reason == null || reason.isBlank() ? fallback : reason;
  }

  private String firstNonBlank(String firstValue, String secondValue) {
    return firstValue != null && !firstValue.isBlank() ? firstValue : secondValue;
  }

  private <T> T firstNonNull(T firstValue, T secondValue) {
    return firstValue != null ? firstValue : secondValue;
  }

  private record StrategyCandidate(
      String strategy,
      InterviewIntent intent,
      double confidence,
      double weight,
      String reason,
      IntentRecognitionResult.Entities entities
  ) {
    StrategyCandidate(String strategy, InterviewIntent intent, double confidence, double weight,
                      String reason) {
      this(strategy, intent, confidence, weight, reason, null);
    }
  }
}
