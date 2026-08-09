package com.linrun.interview.business.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.ai.service.PromptSanitizer;
import com.linrun.interview.ai.service.StructuredOutputInvoker;
import com.linrun.interview.document.service.FileHashService;
import com.linrun.interview.business.vo.CapabilityAtomDTO;
import com.linrun.interview.business.vo.CapabilityTemplateDTO;
import com.linrun.interview.business.constant.JobTrack;
import com.linrun.interview.business.service.CapabilityCatalogService;
import com.linrun.interview.business.job.CapabilityMappingSource;
import com.linrun.interview.business.job.JobDescriptionEntity;
import com.linrun.interview.business.job.JobDescriptionStatus;
import com.linrun.interview.business.job.JdAnalysisService.JdAnalysisOutput;
import com.linrun.interview.business.job.JdAnalysisService.JdRequirement;
import com.linrun.interview.business.job.JobCapabilityMappingService.MappingDraft;
import dev.langchain4j.model.chat.ChatModel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

@DisplayName("JD 到能力原子映射")
class JdAnalysisServiceTest {

  private JobDescriptionService jobDescriptionService;
  private JobCapabilityMappingService mappingService;
  private CapabilityCatalogService catalogService;
  private LlmProviderRegistry registry;
  private StructuredOutputInvoker invoker;
  private PromptSanitizer sanitizer;
  private JdAnalysisService service;

  @BeforeEach
  void setUp() {
    jobDescriptionService = mock(JobDescriptionService.class);
    mappingService = mock(JobCapabilityMappingService.class);
    catalogService = mock(CapabilityCatalogService.class);
    registry = mock(LlmProviderRegistry.class);
    invoker = mock(StructuredOutputInvoker.class);
    sanitizer = mock(PromptSanitizer.class);
    service = new JdAnalysisService(
        jobDescriptionService,
        mappingService,
        catalogService,
        registry,
        invoker,
        sanitizer,
        new FileHashService());
  }

  @Test
  @DisplayName("只接受白名单 atomId 和可在原文定位的 span")
  @SuppressWarnings("unchecked")
  void shouldPersistWhitelistedSpan() {
    String jdText = "Spring Boot 项目要求掌握事务、Redis 与线上故障定位，并能清楚说明技术取舍和系统边界。";
    prepare(jdText);
    JdAnalysisOutput output = new JdAnalysisOutput(List.of(new JdRequirement(
        "ATOM_A", null, "Spring Boot", 0, 11,
        new BigDecimal("0.90"), new BigDecimal("0.60"))));
    when(invoker.invoke(any(), anyString(), anyString(), eq(JdAnalysisOutput.class),
        any(), anyString(), anyString(), any(Logger.class))).thenReturn(output);

    var result = service.analyze(1L, 10L);

    assertThat(result.fallbackUsed()).isFalse();
    ArgumentCaptor<List<MappingDraft>> captor = ArgumentCaptor.forClass(List.class);
    verify(mappingService).replaceAnalysis(eq(1L), eq(10L), captor.capture());
    assertThat(captor.getValue()).hasSize(3);
    assertThat(captor.getValue().getFirst().mappingSource()).isEqualTo(CapabilityMappingSource.JD_LLM);
    assertThat(captor.getValue().getFirst().evidenceText()).isEqualTo("Spring Boot");
    BigDecimal totalWeight = captor.getValue().stream()
        .map(MappingDraft::suggestedWeight)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(totalWeight).isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  @DisplayName("模型证据无法回指原文时回退岗位基线")
  @SuppressWarnings("unchecked")
  void shouldFallbackWhenEvidenceCannotBeVerified() {
    String jdText = "Java 后端岗位要求掌握数据库、缓存和消息队列，能够完成系统设计并定位生产环境故障。";
    prepare(jdText);
    JdAnalysisOutput output = new JdAnalysisOutput(List.of(new JdRequirement(
        "ATOM_A", null, "原文并不存在", 0, 6,
        new BigDecimal("0.90"), new BigDecimal("0.60"))));
    when(invoker.invoke(any(), anyString(), anyString(), eq(JdAnalysisOutput.class),
        any(), anyString(), anyString(), any(Logger.class))).thenReturn(output);

    var result = service.analyze(1L, 10L);

    assertThat(result.fallbackUsed()).isTrue();
    ArgumentCaptor<List<MappingDraft>> captor = ArgumentCaptor.forClass(List.class);
    verify(mappingService).replaceAnalysis(eq(1L), eq(10L), captor.capture());
    assertThat(captor.getValue()).allMatch(
        draft -> draft.mappingSource() == CapabilityMappingSource.BASELINE_FALLBACK);
  }

  private void prepare(String jdText) {
    JobDescriptionEntity job = JobDescriptionEntity.builder()
        .id(10L)
        .userId(1L)
        .version(1)
        .jdText(jdText)
        .status(JobDescriptionStatus.DRAFT)
        .templateCode("TEST")
        .templateVersion("1.0.0")
        .build();
    CapabilityTemplateDTO template = new CapabilityTemplateDTO(
        "TEST", JobTrack.JAVA_BACKEND, "1.0.0", "hash", LocalDate.now(), List.of(
        atom("ATOM_A", "0.50"), atom("ATOM_B", "0.30"), atom("ATOM_C", "0.20")));
    when(jobDescriptionService.requireOwned(1L, 10L)).thenReturn(job);
    when(catalogService.getTemplate("TEST", "1.0.0")).thenReturn(template);
    when(registry.getUserChatModel(1L)).thenReturn(mock(ChatModel.class));
    when(sanitizer.sanitize(jdText)).thenReturn(jdText);
    when(sanitizer.wrapWithDelimiters("job-description", jdText)).thenReturn(jdText);
    when(mappingService.replaceAnalysis(anyLong(), anyLong(), any())).thenReturn(List.of());
  }

  private CapabilityAtomDTO atom(String id, String weight) {
    return new CapabilityAtomDTO(
        id, "1.0.0", id, "description", "DOMAIN", null,
        new BigDecimal(weight), 1, List.of("CONCEPT"));
  }
}
