package com.linrun.interview.common.evidence;

import java.util.Objects;

/** 可跨检索、报告和历史快照稳定引用的证据定位。 */
public record EvidenceRef(
    String evidenceId,
    DataDomain dataDomain,
    String resourceId,
    String resourceVersion,
    String sourceType,
    String sourceLocator,
    String contentHash
) {

  public EvidenceRef {
    evidenceId = requireText(evidenceId, "evidenceId");
    dataDomain = Objects.requireNonNull(dataDomain, "dataDomain");
    resourceId = requireText(resourceId, "resourceId");
    resourceVersion = requireText(resourceVersion, "resourceVersion");
    sourceType = requireText(sourceType, "sourceType");
    sourceLocator = requireText(sourceLocator, "sourceLocator");
    contentHash = requireText(contentHash, "contentHash");
  }

  public static EvidenceRef from(EvidenceMetadata metadata) {
    Objects.requireNonNull(metadata, "metadata");
    return new EvidenceRef(
        metadata.evidenceId(),
        metadata.dataDomain(),
        metadata.resourceId(),
        metadata.resourceVersion(),
        metadata.sourceType(),
        metadata.sourceLocator(),
        metadata.contentHash());
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value.trim();
  }
}
