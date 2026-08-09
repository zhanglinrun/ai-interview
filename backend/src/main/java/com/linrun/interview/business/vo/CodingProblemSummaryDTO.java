package com.linrun.interview.business.vo;

import com.linrun.interview.business.constant.CodingLanguage;
import com.linrun.interview.business.constant.ProblemDifficulty;
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
