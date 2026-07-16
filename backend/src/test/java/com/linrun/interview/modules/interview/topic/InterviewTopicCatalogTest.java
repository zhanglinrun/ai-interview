package com.linrun.interview.modules.interview.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.linrun.interview.modules.capability.dto.CapabilityAtomDTO;
import com.linrun.interview.modules.capability.dto.CapabilityTemplateDTO;
import com.linrun.interview.modules.capability.model.JobTrack;
import com.linrun.interview.modules.capability.service.CapabilityCatalogService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("面试主题目录测试")
class InterviewTopicCatalogTest {

  private CapabilityCatalogService capabilityCatalogService;
  private InterviewTopicCatalog catalog;

  @BeforeEach
  void setUp() {
    capabilityCatalogService = mock(CapabilityCatalogService.class);
    catalog = new InterviewTopicCatalog(capabilityCatalogService);
  }

  @Test
  @DisplayName("预设主题只投影已发布的版本化能力模板")
  void mapsPublishedCapabilityTemplate() {
    CapabilityTemplateDTO template = template();
    when(capabilityCatalogService.getPublishedTemplate(JobTrack.JAVA_BACKEND))
        .thenReturn(template);

    InterviewTopic topic = catalog.getTopic("java-backend");

    assertThat(topic.templateCode()).isEqualTo("JAVA_BACKEND_BASELINE");
    assertThat(topic.templateVersion()).isEqualTo("1.0.0");
    assertThat(topic.categories())
        .extracting(InterviewTopic.Category::key)
        .containsExactly("JAVA_LANGUAGE_FOUNDATION", "BACKEND_SYSTEM_DESIGN");
    assertThat(topic.categories().getFirst().definitionVersion()).isEqualTo("1.0.0");
  }

  @Test
  @DisplayName("题量先覆盖核心能力，再轮转分配剩余题目")
  void allocatesQuestionsByTemplatePriority() {
    InterviewTopic topic = new InterviewTopic(
        "java-backend", "Java 后端", "", List.of(
            new InterviewTopic.Category("JAVA", "Java", "CORE", "1.0.0"),
            new InterviewTopic.Category("MYSQL", "MySQL", "CORE", "1.0.0"),
            new InterviewTopic.Category("OBSERVABILITY", "可观测性", "NORMAL", "1.0.0")),
        true, null, "JAVA_BACKEND_BASELINE", "1.0.0");

    assertThat(catalog.calculateAllocation(topic.categories(), 5))
        .containsEntry("JAVA", 2)
        .containsEntry("MYSQL", 2)
        .containsEntry("OBSERVABILITY", 1);
  }

  private CapabilityTemplateDTO template() {
    return new CapabilityTemplateDTO(
        "JAVA_BACKEND_BASELINE",
        JobTrack.JAVA_BACKEND,
        "1.0.0",
        "hash",
        LocalDate.of(2026, 7, 15),
        List.of(
            atom("JAVA_LANGUAGE_FOUNDATION", "Java 语言基础", 1),
            atom("BACKEND_SYSTEM_DESIGN", "后端系统设计", 0)));
  }

  private CapabilityAtomDTO atom(String id, String name, int minimumCoverage) {
    return new CapabilityAtomDTO(
        id,
        "1.0.0",
        name,
        name,
        "TECHNICAL",
        null,
        BigDecimal.ONE,
        minimumCoverage,
        List.of("CONCEPT"));
  }
}
