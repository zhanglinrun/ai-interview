package com.linrun.interview.rag.service;

import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.rag.constant.MetadataKeyConstant;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("分域证据加权融合")
class EvidenceRetrievalServiceTest {

  @Test
  @DisplayName("相同证据跨召回路由按 RRF 累加且保留域权重")
  void fusesByEvidenceId() {
    Content candidate = content("e-candidate", "CANDIDATE", "2.0");
    Content platform = content("e-platform", "PLATFORM", "1.0");

    List<Content> result = EvidenceRetrievalService.fuseRankedLists(
        List.of(List.of(candidate), List.of(platform)),
        List.of(2.0d, 1.0d),
        60,
        10);

    assertThat(result).hasSize(2);
    assertThat(result.getFirst().textSegment().metadata()
        .getString(MetadataKeyConstant.EVIDENCE_ID)).isEqualTo("e-candidate");
    assertThat(result.getFirst().textSegment().metadata()
        .getString(MetadataKeyConstant.DOMAIN_WEIGHT)).isEqualTo("2.0");
  }

  private Content content(String evidenceId, String domain, String weight) {
    Metadata metadata = Metadata.from(Map.of(
        MetadataKeyConstant.EVIDENCE_ID, evidenceId,
        MetadataKeyConstant.DATA_DOMAIN, domain,
        MetadataKeyConstant.RESOURCE_ID, "r1",
        MetadataKeyConstant.RESOURCE_VERSION, "v1",
        MetadataKeyConstant.OWNER_USER_ID,
            domain.equals(DataDomain.PLATFORM.name()) ? "0" : "7",
        MetadataKeyConstant.CONTENT_HASH, "hash-" + evidenceId,
        MetadataKeyConstant.SOURCE_TYPE, "TEST",
        MetadataKeyConstant.SOURCE_LOCATOR, evidenceId,
        MetadataKeyConstant.DOMAIN_WEIGHT, weight));
    return Content.from(TextSegment.from("evidence " + evidenceId, metadata));
  }
}
