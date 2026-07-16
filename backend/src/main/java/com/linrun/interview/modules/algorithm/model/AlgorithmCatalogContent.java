package com.linrun.interview.modules.algorithm.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 仓库内 Hot 100 映射和首发题版本的导入契约。 */
public record AlgorithmCatalogContent(
    String schemaVersion,
    String contentVersion,
    String checksum,
    List<ProblemDefinition> problems,
    List<EnabledProblemDefinition> enabledProblems
) {
  public record ProblemDefinition(
      Integer hotRank,
      String platform,
      String platformProblemId,
      String slug,
      String title,
      ProblemDifficulty difficulty,
      List<String> tags,
      String sourceUrl
  ) {
  }

  public record EnabledProblemDefinition(
      String platformProblemId,
      ProblemVersionDefinition version
  ) {
  }

  public record ProblemVersionDefinition(
      String version,
      String statement,
      List<String> constraints,
      List<PublicExampleDefinition> publicExamples,
      ComplexityRubricDefinition complexityRubric,
      List<LanguageSpecDefinition> languages,
      List<TestCaseDefinition> publicTests,
      List<TestCaseDefinition> hiddenTests,
      Boolean enabled
  ) {
  }

  public record PublicExampleDefinition(String input, String output, String explanation) {
  }

  public record ComplexityRubricDefinition(
      String expectedTime,
      String expectedSpace,
      List<String> discussionPoints
  ) {
  }

  public record LanguageSpecDefinition(
      CodingLanguage language,
      Boolean enabled,
      String functionName,
      String returnType,
      List<String> parameterTypes,
      String functionSignature,
      String template,
      String referenceSolution
  ) {
  }

  public record TestCaseDefinition(
      String id,
      JsonNode arguments,
      JsonNode expected,
      String comparisonMode
  ) {
  }
}
