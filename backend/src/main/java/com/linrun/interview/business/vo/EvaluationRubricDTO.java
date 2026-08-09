package com.linrun.interview.business.vo;

import java.math.BigDecimal;
import java.util.List;

public record EvaluationRubricDTO(
    String rubricCode,
    String version,
    List<Dimension> dimensions
) {
  public EvaluationRubricDTO {
    dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
  }

  public record Dimension(String code, String name, BigDecimal weight, String criteria) {
  }
}
