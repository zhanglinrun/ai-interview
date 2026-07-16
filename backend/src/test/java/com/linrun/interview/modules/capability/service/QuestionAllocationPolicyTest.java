package com.linrun.interview.modules.capability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.modules.capability.dto.CapabilityAtomDTO;
import com.linrun.interview.modules.capability.dto.CapabilityTemplateDTO;
import com.linrun.interview.modules.capability.model.JobTrack;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("确定性题量分配")
class QuestionAllocationPolicyTest {

  private final QuestionAllocationPolicy policy = new QuestionAllocationPolicy();

  @Test
  @DisplayName("先满足最低覆盖再按权重补齐")
  void shouldAllocateByMinimumAndWeight() {
    CapabilityTemplateDTO template = template(
        atom("ATOM_A", "0.60", 1),
        atom("ATOM_B", "0.40", 1));

    var allocation = policy.allocate(template, 10);

    assertThat(allocation).containsEntry("ATOM_A", 6).containsEntry("ATOM_B", 4);
    assertThat(allocation.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(10);
  }

  @Test
  @DisplayName("总题量低于最低覆盖时明确拒绝")
  void shouldRejectInsufficientQuestionCount() {
    CapabilityTemplateDTO template = template(
        atom("ATOM_A", "0.50", 2),
        atom("ATOM_B", "0.50", 2));

    assertThatThrownBy(() -> policy.allocate(template, 3))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("最低覆盖");
  }

  private CapabilityTemplateDTO template(CapabilityAtomDTO... atoms) {
    return new CapabilityTemplateDTO(
        "TEST", JobTrack.JAVA_BACKEND, "1.0.0", "hash", LocalDate.now(), List.of(atoms));
  }

  private CapabilityAtomDTO atom(String id, String weight, int minimum) {
    return new CapabilityAtomDTO(
        id, "1.0.0", id, "description", "DOMAIN", null,
        new BigDecimal(weight), minimum, List.of("CONCEPT"));
  }
}
