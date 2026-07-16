package com.linrun.interview.common.evidence;

import java.util.Map;
import java.util.Objects;

/** 写入统一 ES 物理索引的通用片段。 */
public record EvidenceIndexChunk(
    String text,
    EvidenceMetadata metadata,
    Map<String, String> additionalMetadata
) {

  public EvidenceIndexChunk {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("text 不能为空");
    }
    metadata = Objects.requireNonNull(metadata, "metadata");
    additionalMetadata = additionalMetadata == null
        ? Map.of() : Map.copyOf(additionalMetadata);
  }
}
