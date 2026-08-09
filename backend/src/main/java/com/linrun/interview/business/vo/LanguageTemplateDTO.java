package com.linrun.interview.business.vo;

import com.linrun.interview.business.constant.CodingLanguage;

public record LanguageTemplateDTO(
    CodingLanguage language,
    String functionSignature,
    String template
) {
}
