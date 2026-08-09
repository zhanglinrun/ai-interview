package com.linrun.interview.business.vo;

import com.linrun.interview.business.constant.ProblemDifficulty;
import java.util.List;

/** 不包含参考实现和隐藏用例。 */
public record CodingProblemDetailDTO(
    Long problemId,
    Long problemVersionId,
    Integer hotRank,
    String platformProblemId,
    String title,
    ProblemDifficulty difficulty,
    List<String> tags,
    String sourceUrl,
    String version,
    String statement,
    List<String> constraints,
    List<PublicExampleDTO> publicExamples,
    ComplexityRubricDTO complexityRubric,
    List<LanguageTemplateDTO> languages
) {
  public record ComplexityRubricDTO(
      String expectedTime,
      String expectedSpace,
      List<String> discussionPoints
  ) {
  }
}
