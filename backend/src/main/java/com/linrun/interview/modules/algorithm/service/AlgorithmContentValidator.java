package com.linrun.interview.modules.algorithm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linrun.interview.modules.algorithm.model.AlgorithmCatalogContent;
import com.linrun.interview.modules.algorithm.model.AlgorithmCatalogContent.LanguageSpecDefinition;
import com.linrun.interview.modules.algorithm.model.AlgorithmCatalogContent.TestCaseDefinition;
import com.linrun.interview.modules.algorithm.model.CodingLanguage;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Hot 100 内容结构校验；发布版本不允许少题、重复或泄漏隐藏用例。 */
@Component
public class AlgorithmContentValidator {

  public static final int HOT_100_SIZE = 100;
  public static final int V1_ENABLED_SIZE = 20;
  public static final int MIN_PUBLIC_TESTS = 2;
  public static final int MIN_HIDDEN_TESTS = 3;
  private static final String CHECKSUM_PREFIX = "sha256:";
  private static final Set<String> VALUE_TYPES = Set.of(
      "INT", "BOOLEAN", "STRING", "INT_ARRAY", "INT_MATRIX", "CHAR_MATRIX",
      "LIST_NODE", "TREE_NODE", "LIST_INT", "LIST_LIST_INT");
  private static final Set<String> COMPARISON_MODES = Set.of(
      "ORDERED", "UNORDERED_ROWS", "UNORDERED_ROWS_AND_VALUES");

  private final ObjectMapper objectMapper;

  public AlgorithmContentValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ValidationReport validate(AlgorithmCatalogContent content) {
    List<String> errors = new ArrayList<>();
    if (content == null) {
      return new ValidationReport(false, List.of("内容文件为空"), 0, 0, null);
    }
    require("1.0.0".equals(content.schemaVersion()),
        "不支持的 schemaVersion: " + content.schemaVersion(), errors);
    require(!blank(content.contentVersion()), "contentVersion 必填", errors);
    String calculatedChecksum = calculateChecksum(content);
    require((CHECKSUM_PREFIX + calculatedChecksum).equalsIgnoreCase(content.checksum()),
        "checksum 不匹配，应为 " + CHECKSUM_PREFIX + calculatedChecksum, errors);

    List<AlgorithmCatalogContent.ProblemDefinition> problems = safe(content.problems());
    require(problems.size() == HOT_100_SIZE, "Hot 100 映射必须恰好包含 100 道题", errors);
    Set<String> platformIds = new HashSet<>();
    Set<String> slugs = new HashSet<>();
    Set<Integer> ranks = new HashSet<>();
    for (var problem : problems) {
      if (problem == null) {
        errors.add("Hot 100 映射不能包含空题目");
        continue;
      }
      require(problem.hotRank() != null && problem.hotRank() >= 1
              && problem.hotRank() <= HOT_100_SIZE && ranks.add(problem.hotRank()),
          "hotRank 必须在 1..100 内且不可重复: " + problem.hotRank(), errors);
      require(!blank(problem.platformProblemId())
              && platformIds.add(problem.platformProblemId()),
          "platformProblemId 必填且不可重复: " + problem.platformProblemId(), errors);
      require(!blank(problem.slug()) && slugs.add(problem.slug()),
          "slug 必填且不可重复: " + problem.slug(), errors);
      require("LEETCODE".equals(problem.platform()),
          "Hot 100 首发目录只接受 LEETCODE 映射: " + problem.platformProblemId(), errors);
      require(!blank(problem.title()) && problem.difficulty() != null
              && problem.tags() != null && !problem.tags().isEmpty(),
          "题目映射缺少标题、难度或标签: " + problem.platformProblemId(), errors);
      require(("https://leetcode.cn/problems/" + problem.slug() + "/")
              .equals(problem.sourceUrl()),
          "题目来源链接必须与 slug 对应: " + problem.platformProblemId(), errors);
    }

    List<AlgorithmCatalogContent.EnabledProblemDefinition> enabledProblems =
        safe(content.enabledProblems());
    require(enabledProblems.size() == V1_ENABLED_SIZE,
        "V1 必须恰好启用 20 道自有题面", errors);
    Set<String> enabledIds = new HashSet<>();
    for (var enabled : enabledProblems) {
      if (enabled == null || blank(enabled.platformProblemId())) {
        errors.add("启用题的 platformProblemId 必填");
        continue;
      }
      require(platformIds.contains(enabled.platformProblemId()),
          "启用题必须来自 Hot 100 映射: " + enabled.platformProblemId(), errors);
      require(enabledIds.add(enabled.platformProblemId()),
          "启用题不可重复: " + enabled.platformProblemId(), errors);
      if (enabled.version() == null || !Boolean.TRUE.equals(enabled.version().enabled())) {
        errors.add("启用题缺少有效版本: " + enabled.platformProblemId());
        continue;
      }
      validateVersion(enabled.platformProblemId(), enabled.version(), errors);
    }
    return new ValidationReport(
        errors.isEmpty(), List.copyOf(errors), problems.size(), enabledProblems.size(),
        calculatedChecksum);
  }

  /** 对 checksum 置空且递归按属性名排序后的语义 JSON 计算 SHA-256。 */
  public String calculateChecksum(AlgorithmCatalogContent content) {
    try {
      ObjectNode root = objectMapper.valueToTree(content);
      root.put("checksum", "");
      byte[] canonical = objectMapper.writeValueAsBytes(sortNode(root));
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonical));
    } catch (Exception e) {
      throw new IllegalStateException("计算算法题库 checksum 失败", e);
    }
  }

  private void validateVersion(
      String platformId,
      AlgorithmCatalogContent.ProblemVersionDefinition version,
      List<String> errors
  ) {
    require(!blank(version.version()) && !blank(version.statement()),
        "启用题缺少版本化题面: " + platformId, errors);
    require(version.constraints() != null && !version.constraints().isEmpty(),
        "启用题缺少约束: " + platformId, errors);
    require(version.publicExamples() != null && !version.publicExamples().isEmpty(),
        "启用题缺少公开示例: " + platformId, errors);
    require(version.complexityRubric() != null
            && !blank(version.complexityRubric().expectedTime())
            && !blank(version.complexityRubric().expectedSpace())
            && version.complexityRubric().discussionPoints() != null
            && !version.complexityRubric().discussionPoints().isEmpty(),
        "启用题缺少复杂度 Rubric: " + platformId, errors);

    List<LanguageSpecDefinition> languages = safe(version.languages());
    Set<CodingLanguage> languageSet = new HashSet<>();
    for (LanguageSpecDefinition language : languages) {
      validateLanguage(platformId, language, languageSet, errors);
    }
    require(languageSet.equals(Set.of(CodingLanguage.JAVA21, CodingLanguage.PYTHON3)),
        "启用题必须且只能提供 Java 21 与 Python 3: " + platformId, errors);

    List<TestCaseDefinition> publicTests = safe(version.publicTests());
    List<TestCaseDefinition> hiddenTests = safe(version.hiddenTests());
    require(publicTests.size() >= MIN_PUBLIC_TESTS,
        "启用题至少需要 " + MIN_PUBLIC_TESTS + " 个公开测试: " + platformId, errors);
    require(hiddenTests.size() >= MIN_HIDDEN_TESTS,
        "启用题至少需要 " + MIN_HIDDEN_TESTS + " 个隐藏测试: " + platformId, errors);
    LanguageSpecDefinition reference = languages.stream()
        .filter(item -> item != null && item.language() == CodingLanguage.JAVA21)
        .findFirst().orElse(null);
    if (reference != null) {
      Set<String> ids = new HashSet<>();
      Set<String> publicFingerprints = new HashSet<>();
      for (TestCaseDefinition test : publicTests) {
        validateTest(platformId, "公开", test, reference, ids, errors);
        if (test != null) {
          publicFingerprints.add(fingerprint(test));
        }
      }
      for (TestCaseDefinition test : hiddenTests) {
        validateTest(platformId, "隐藏", test, reference, ids, errors);
        if (test != null) {
          require(!publicFingerprints.contains(fingerprint(test)),
              "隐藏测试不能复制公开测试: " + platformId + "/" + test.id(), errors);
        }
      }
    }
  }

  private void validateLanguage(
      String platformId,
      LanguageSpecDefinition language,
      Set<CodingLanguage> languageSet,
      List<String> errors
  ) {
    if (language == null || language.language() == null) {
      errors.add("启用题包含空语言配置: " + platformId);
      return;
    }
    require(languageSet.add(language.language()),
        "语言配置不可重复: " + platformId + "/" + language.language(), errors);
    require(Boolean.TRUE.equals(language.enabled()),
        "首发双语言必须启用: " + platformId + "/" + language.language(), errors);
    require(!blank(language.functionName())
            && language.functionName().matches("[A-Za-z_][A-Za-z0-9_]*"),
        "函数名非法: " + platformId + "/" + language.language(), errors);
    require(VALUE_TYPES.contains(language.returnType()),
        "返回类型非法: " + platformId + "/" + language.language(), errors);
    require(language.parameterTypes() != null
            && language.parameterTypes().stream().allMatch(VALUE_TYPES::contains),
        "参数类型非法: " + platformId + "/" + language.language(), errors);
    require(!blank(language.functionSignature()) && !blank(language.template())
            && !blank(language.referenceSolution()),
        "签名、模板和参考实现均必填: " + platformId + "/" + language.language(), errors);
    if (!blank(language.template()) && !blank(language.functionName())) {
      require(language.template().contains("class Solution")
              && language.template().contains(language.functionName()),
          "模板必须包含 Solution 与目标函数: " + platformId + "/" + language.language(), errors);
    }
    if (!blank(language.referenceSolution()) && !blank(language.functionName())) {
      require(language.referenceSolution().contains("class Solution")
              && language.referenceSolution().contains(language.functionName()),
          "参考实现必须包含 Solution 与目标函数: "
              + platformId + "/" + language.language(), errors);
      require(!language.referenceSolution().strip().equals(language.template().strip()),
          "参考实现不能与初始模板相同: " + platformId + "/" + language.language(), errors);
    }
  }

  private void validateTest(
      String platformId,
      String suite,
      TestCaseDefinition test,
      LanguageSpecDefinition spec,
      Set<String> ids,
      List<String> errors
  ) {
    if (test == null) {
      errors.add(suite + "测试不能为 null: " + platformId);
      return;
    }
    require(!blank(test.id()) && ids.add(test.id()),
        "测试 id 必填且不可重复: " + platformId + "/" + test.id(), errors);
    require(test.arguments() != null && test.arguments().isArray()
            && test.arguments().size() == spec.parameterTypes().size(),
        suite + "测试参数数量错误: " + platformId + "/" + test.id(), errors);
    if (test.arguments() != null && test.arguments().isArray()
        && test.arguments().size() == spec.parameterTypes().size()) {
      for (int i = 0; i < spec.parameterTypes().size(); i++) {
        require(valueMatches(test.arguments().get(i), spec.parameterTypes().get(i)),
            suite + "测试参数类型错误: " + platformId + "/" + test.id() + "/" + i,
            errors);
      }
    }
    require(valueMatches(test.expected(), spec.returnType()),
        suite + "测试期望值类型错误: " + platformId + "/" + test.id(), errors);
    String mode = blank(test.comparisonMode()) ? "ORDERED" : test.comparisonMode();
    require(COMPARISON_MODES.contains(mode),
        "测试比较模式非法: " + platformId + "/" + test.id(), errors);
    require("LIST_LIST_INT".equals(spec.returnType()) || "ORDERED".equals(mode),
        "非二维列表返回值只能使用 ORDERED 比较: " + platformId + "/" + test.id(), errors);
  }

  private boolean valueMatches(JsonNode value, String type) {
    if (value == null || type == null) {
      return false;
    }
    return switch (type) {
      case "INT" -> value.isIntegralNumber();
      case "BOOLEAN" -> value.isBoolean();
      case "STRING" -> value.isTextual();
      case "INT_ARRAY", "LIST_NODE", "LIST_INT" -> isArrayOfIntegers(value, false);
      case "TREE_NODE" -> isArrayOfIntegers(value, true);
      case "INT_MATRIX", "LIST_LIST_INT" -> value.isArray()
          && every(value, item -> isArrayOfIntegers(item, false));
      case "CHAR_MATRIX" -> value.isArray() && every(value, JsonNode::isTextual);
      default -> false;
    };
  }

  private boolean isArrayOfIntegers(JsonNode value, boolean nullable) {
    return value.isArray()
        && every(value, item -> item.isIntegralNumber() || nullable && item.isNull());
  }

  private boolean every(JsonNode array, java.util.function.Predicate<JsonNode> predicate) {
    for (JsonNode item : array) {
      if (!predicate.test(item)) {
        return false;
      }
    }
    return true;
  }

  private String fingerprint(TestCaseDefinition test) {
    return String.valueOf(test.arguments()) + '|' + test.expected() + '|'
        + (blank(test.comparisonMode()) ? "ORDERED" : test.comparisonMode());
  }

  private JsonNode sortNode(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = objectMapper.createObjectNode();
      List<String> names = new ArrayList<>();
      Iterator<String> fields = node.fieldNames();
      fields.forEachRemaining(names::add);
      names.sort(Comparator.naturalOrder());
      names.forEach(name -> sorted.set(name, sortNode(node.get(name))));
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode sorted = objectMapper.createArrayNode();
      node.forEach(item -> sorted.add(sortNode(item)));
      return sorted;
    }
    return node;
  }

  private <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  private void require(boolean condition, String error, List<String> errors) {
    if (!condition) {
      errors.add(error);
    }
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public record ValidationReport(
      boolean valid,
      List<String> errors,
      int problemCount,
      int enabledCount,
      String calculatedChecksum
  ) {
  }
}
