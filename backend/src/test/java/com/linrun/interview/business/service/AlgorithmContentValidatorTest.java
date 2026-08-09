package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linrun.interview.business.vo.AlgorithmCatalogContent;
import com.linrun.interview.business.constant.CodingLanguage;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Hot 100 内容发布校验")
class AlgorithmContentValidatorTest {

  private ObjectMapper objectMapper;
  private AlgorithmContentValidator validator;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    validator = new AlgorithmContentValidator(objectMapper);
  }

  @Test
  @DisplayName("仓库题库应通过 checksum、双语言和测试结构校验")
  void shouldValidatePublishedCatalog() throws IOException {
    AlgorithmCatalogContent content = load();

    var report = validator.validate(content);

    assertThat(report.valid()).as(report.errors().toString()).isTrue();
    assertThat(report.problemCount()).isEqualTo(100);
    assertThat(report.enabledCount()).isEqualTo(20);
    assertThat(content.checksum()).isEqualTo("sha256:" + report.calculatedChecksum());
    assertThat(content.enabledProblems()).allSatisfy(enabled -> {
      assertThat(enabled.version().publicTests())
          .hasSizeGreaterThanOrEqualTo(AlgorithmContentValidator.MIN_PUBLIC_TESTS);
      assertThat(enabled.version().hiddenTests())
          .hasSizeGreaterThanOrEqualTo(AlgorithmContentValidator.MIN_HIDDEN_TESTS);
      assertThat(enabled.version().languages())
          .extracting(AlgorithmCatalogContent.LanguageSpecDefinition::language)
          .containsExactlyInAnyOrder(CodingLanguage.JAVA21, CodingLanguage.PYTHON3);
      assertThat(enabled.version().languages()).allSatisfy(language -> {
        assertThat(language.template()).contains("class Solution", language.functionName());
        assertThat(language.referenceSolution())
            .contains("class Solution", language.functionName())
            .isNotEqualTo(language.template());
      });
    });
  }

  @Test
  @DisplayName("参考实现缺失时必须拒绝发布")
  void shouldRejectMissingReferenceSolution() throws IOException {
    ObjectNode root = loadTree();
    ObjectNode language = (ObjectNode) root.path("enabledProblems").path(0)
        .path("version").path("languages").path(0);
    language.put("referenceSolution", "");

    var report = validator.validate(objectMapper.treeToValue(root, AlgorithmCatalogContent.class));

    assertThat(report.valid()).isFalse();
    assertThat(report.errors()).anyMatch(error -> error.contains("参考实现均必填"));
  }

  @Test
  @DisplayName("隐藏用例复制公开输入输出时必须拒绝发布")
  void shouldRejectLeakedPublicTest() throws IOException {
    ObjectNode root = loadTree();
    ObjectNode version = (ObjectNode) root.path("enabledProblems").path(0).path("version");
    ObjectNode duplicate = version.path("publicTests").path(0).deepCopy();
    duplicate.put("id", "h-copy");
    version.withArray("hiddenTests").set(0, duplicate);

    var report = validator.validate(objectMapper.treeToValue(root, AlgorithmCatalogContent.class));

    assertThat(report.valid()).isFalse();
    assertThat(report.errors()).anyMatch(error -> error.contains("隐藏测试不能复制公开测试"));
  }

  @Test
  @DisplayName("用例参数类型与函数签名不一致时必须拒绝发布")
  void shouldRejectInvalidArgumentType() throws IOException {
    ObjectNode root = loadTree();
    ArrayNode arguments = (ArrayNode) root.path("enabledProblems").path(0)
        .path("version").path("publicTests").path(0).path("arguments");
    arguments.set(1, objectMapper.getNodeFactory().textNode("9"));

    var report = validator.validate(objectMapper.treeToValue(root, AlgorithmCatalogContent.class));

    assertThat(report.valid()).isFalse();
    assertThat(report.errors()).anyMatch(error -> error.contains("测试参数类型错误"));
  }

  private AlgorithmCatalogContent load() throws IOException {
    try (InputStream input = resource()) {
      return objectMapper.readValue(input, AlgorithmCatalogContent.class);
    }
  }

  private ObjectNode loadTree() throws IOException {
    try (InputStream input = resource()) {
      return (ObjectNode) objectMapper.readTree(input);
    }
  }

  private InputStream resource() {
    InputStream input = getClass().getClassLoader()
        .getResourceAsStream("algorithm-content/hot100-v1.json");
    if (input == null) {
      throw new IllegalStateException("测试题库资源不存在");
    }
    return input;
  }
}
