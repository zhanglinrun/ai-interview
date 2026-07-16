package com.linrun.interview.modules.capability.model;

import java.math.BigDecimal;
import java.util.List;

/** Git 管理的能力目录文件契约。 */
public record CapabilityCatalogContent(
    String schemaVersion,
    String contentVersion,
    ContentSource source,
    String effectiveDate,
    String checksum,
    List<AtomContent> atoms,
    List<TemplateContent> templates,
    List<QuestionTemplateContent> questionTemplates,
    List<RubricContent> rubrics,
    List<PlatformKnowledgeContent> platformKnowledge
) {

  public record ContentSource(String name, String locator) {
  }

  public record AtomContent(
      String atomId,
      String version,
      String name,
      String description,
      String capabilityDomain,
      List<JobTrack> jobTracks,
      String parentAtomId
  ) {
  }

  public record TemplateContent(
      String templateCode,
      JobTrack jobTrack,
      String version,
      CatalogStatus status,
      List<TemplateCapabilityContent> capabilities
  ) {
  }

  public record TemplateCapabilityContent(
      String atomId,
      String atomVersion,
      BigDecimal defaultWeight,
      Integer minimumCoverage,
      List<String> questionTypes
  ) {
  }

  public record QuestionTemplateContent(
      String questionCode,
      String version,
      CatalogStatus status,
      String atomId,
      String atomVersion,
      String difficulty,
      String stage,
      String promptSkeleton,
      String rubricCode,
      String rubricVersion
  ) {
  }

  public record RubricContent(
      String rubricCode,
      String version,
      CatalogStatus status,
      List<RubricDimensionContent> dimensions
  ) {
  }

  public record RubricDimensionContent(
      String code,
      String name,
      BigDecimal weight,
      String criteria
  ) {
  }

  public record PlatformKnowledgeContent(
      String evidenceId,
      String resourceId,
      String resourceVersion,
      String title,
      String summary,
      String sourceType,
      String sourceLocator,
      String contentHash,
      List<String> capabilityAtomIds
  ) {
  }
}
