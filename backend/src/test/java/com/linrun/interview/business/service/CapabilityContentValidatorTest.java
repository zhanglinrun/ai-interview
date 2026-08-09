package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.rag.model.EvidenceMetadata;
import com.linrun.interview.business.vo.CapabilityCatalogContent;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("能力内容文件校验")
class CapabilityContentValidatorTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
  private final CapabilityContentValidator validator = new CapabilityContentValidator(objectMapper);

  @Test
  @DisplayName("内置两套岗位基线通过完整校验")
  void shouldValidateBuiltinCatalog() throws Exception {
    CapabilityCatalogContent content = objectMapper.readValue(
        new ClassPathResource(ContentImportService.CATALOG_RESOURCE).getInputStream(),
        CapabilityCatalogContent.class);

    var report = validator.validate(content);

    assertThat(report.errors()).withFailMessage(report.errors().toString()).isEmpty();
    assertThat(report.counts().templates()).isEqualTo(2);
    assertThat(report.counts().atoms()).isGreaterThanOrEqualTo(10);
    assertThat(content.templates()).extracting(CapabilityCatalogContent.TemplateContent::jobTrack)
        .containsExactlyInAnyOrder(
            com.linrun.interview.business.constant.JobTrack.JAVA_BACKEND,
            com.linrun.interview.business.constant.JobTrack.AI_RAG_AGENT);
  }

  @Test
  @DisplayName("重复能力原子即使 checksum 正确也会拒绝")
  void shouldRejectDuplicateAtom() throws Exception {
    CapabilityCatalogContent original = objectMapper.readValue(
        new ClassPathResource(ContentImportService.CATALOG_RESOURCE).getInputStream(),
        CapabilityCatalogContent.class);
    var atoms = new ArrayList<>(original.atoms());
    atoms.add(original.atoms().getFirst());
    CapabilityCatalogContent invalid = withChecksum(new CapabilityCatalogContent(
        original.schemaVersion(), original.contentVersion(), original.source(), original.effectiveDate(),
        "", atoms, original.templates(), original.questionTemplates(), original.rubrics(),
        original.platformKnowledge()));

    var report = validator.validate(invalid);

    assertThat(report.valid()).isFalse();
    assertThat(report.errors()).anyMatch(error -> error.contains("能力原子重复"));
  }

  @Nested
  @DisplayName("四域 owner 契约")
  class EvidenceOwnerContract {

    @Test
    @DisplayName("平台资料必须显式使用 owner 0")
    void platformRequiresExplicitOwner() {
      EvidenceMetadata metadata = new EvidenceMetadata(
          0L, DataDomain.PLATFORM, "resource", "v1", "evidence", "hash", "DOC", "source");

      assertThat(metadata.ownerUserId()).isZero();
    }

    @Test
    @DisplayName("私有域不能使用平台 owner")
    void privateDomainRejectsPlatformOwner() {
      org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
          .isThrownBy(() -> new EvidenceMetadata(
              0L, DataDomain.JOB, "resource", "v1", "evidence", "hash", "JD", "source"));
    }
  }

  private CapabilityCatalogContent withChecksum(CapabilityCatalogContent content) {
    String checksum = "sha256:" + validator.calculateChecksum(content);
    return new CapabilityCatalogContent(
        content.schemaVersion(), content.contentVersion(), content.source(), content.effectiveDate(),
        checksum, content.atoms(), content.templates(), content.questionTemplates(), content.rubrics(),
        content.platformKnowledge());
  }
}
