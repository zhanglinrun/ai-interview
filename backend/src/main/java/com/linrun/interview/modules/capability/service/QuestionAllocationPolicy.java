package com.linrun.interview.modules.capability.service;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.capability.dto.CapabilityAtomDTO;
import com.linrun.interview.modules.capability.dto.CapabilityTemplateDTO;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 按最低覆盖与权重确定性分配题量，不让 LLM 决定覆盖约束。 */
@Service
public class QuestionAllocationPolicy {

  public Map<String, Integer> allocate(CapabilityTemplateDTO template, int totalQuestions) {
    if (template == null || template.capabilities().isEmpty() || totalQuestions <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "模板和总题量必须有效");
    }
    Map<String, Integer> allocation = new LinkedHashMap<>();
    int allocated = 0;
    for (CapabilityAtomDTO capability : template.capabilities()) {
      int minimum = Math.max(0, capability.minimumCoverage());
      allocation.put(capability.atomId(), minimum);
      allocated += minimum;
    }
    if (allocated > totalQuestions) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "总题量小于模板最低覆盖题量: " + allocated);
    }
    while (allocated < totalQuestions) {
      CapabilityAtomDTO selected = template.capabilities().stream()
          .max(Comparator
              .comparing((CapabilityAtomDTO capability) -> deficit(
                  capability, allocation.get(capability.atomId()), totalQuestions))
              .thenComparing(CapabilityAtomDTO::atomId, Comparator.reverseOrder()))
          .orElseThrow();
      allocation.merge(selected.atomId(), 1, Integer::sum);
      allocated++;
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(allocation));
  }

  private BigDecimal deficit(CapabilityAtomDTO capability, int allocated, int total) {
    return capability.defaultWeight()
        .multiply(BigDecimal.valueOf(total))
        .subtract(BigDecimal.valueOf(allocated));
  }
}
