package com.linrun.interview.modules.algorithm.dto;

import com.linrun.interview.modules.algorithm.model.CodingLanguage;
import com.linrun.interview.modules.algorithm.model.ProblemDifficulty;
import java.util.List;

public record CodingProblemSummaryDTO(
    Long problemId,
    Long problemVersionId,
    Integer hotRank,
    String platformProblemId,
    String title,
    ProblemDifficulty difficulty,
    List<String> tags,
    String sourceUrl,
    String version,
    List<CodingLanguage> enabledLanguages
) {
}
