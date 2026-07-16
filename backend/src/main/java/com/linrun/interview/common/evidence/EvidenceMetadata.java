package com.linrun.interview.common.evidence;

import java.util.Objects;

/**
 * MySQL、Elasticsearch 与 API 共享的证据元数据最小契约。
 *
 * <p>任何检索都必须同时使用 owner、domain 与 resourceId；resourceVersion 用于冻结历史证据。
 */
public record EvidenceMetadata(
    Long ownerUserId,
    DataDomain dataDomain,
    String resourceId,
    String resourceVersion,
    String evidenceId,
    String contentHash,
    String sourceType,
    String sourceLocator
) {

  public EvidenceMetadata {
    dataDomain = Objects.requireNonNull(dataDomain, "dataDomain");
    ownerUserId = requireOwner(ownerUserId, dataDomain);
    resourceId = requireText(resourceId, "resourceId");
    resourceVersion = requireText(resourceVersion, "resourceVersion");
    evidenceId = requireText(evidenceId, "evidenceId");
    contentHash = requireText(contentHash, "contentHash");
    sourceType = requireText(sourceType, "sourceType");
    sourceLocator = requireText(sourceLocator, "sourceLocator");
  }

  private static Long requireOwner(Long ownerUserId, DataDomain dataDomain) {
    if (dataDomain == DataDomain.PLATFORM) {
      if (ownerUserId == null || ownerUserId != DataDomain.PLATFORM_OWNER_USER_ID) {
        throw new IllegalArgumentException("PLATFORM ownerUserId 必须为 0");
      }
      return ownerUserId;
    }
    if (ownerUserId == null || ownerUserId <= 0) {
      throw new IllegalArgumentException(dataDomain + " ownerUserId 必须为有效用户 ID");
    }
    return ownerUserId;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value.trim();
  }
}
