package com.linrun.interview.modules.algorithm.dto;

import com.linrun.interview.modules.algorithm.model.CodingLanguage;

public record LanguageTemplateDTO(
    CodingLanguage language,
    String functionSignature,
    String template
) {
}
