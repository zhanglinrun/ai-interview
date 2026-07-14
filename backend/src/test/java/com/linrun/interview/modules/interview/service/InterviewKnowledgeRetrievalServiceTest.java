package com.linrun.interview.modules.interview.service;

import com.linrun.interview.common.ai.PromptSanitizer;
import com.linrun.interview.modules.interview.agent.model.InterviewEvidence.Bundle;
import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("面试结构化证据检索测试")
class InterviewKnowledgeRetrievalServiceTest {

  @Test
  @DisplayName("保留 chunk ID、来源与 rerank 分数并按证据 ID 去重")
  void keepsEvidenceProvenanceAndScore() {
    KnowledgeBaseQueryService queryService = mock(KnowledgeBaseQueryService.class);
    PromptSanitizer promptSanitizer = mock(PromptSanitizer.class);
    InterviewKnowledgeRetrievalService service =
        new InterviewKnowledgeRetrievalService(queryService, promptSanitizer);

    Metadata metadata = Metadata.from(Map.of(
        MetadataKeyConstant.CHUNK_ID, "chunk-101",
        MetadataKeyConstant.DOC_ID, "9",
        MetadataKeyConstant.FILE_NAME, "岗位说明.md",
        MetadataKeyConstant.CATEGORY, "Java"));
    TextSegment segment = TextSegment.from("需要理解缓存一致性的失败窗口与工程取舍。", metadata);
    Content content = Content.from(segment, Map.of(ContentMetadata.RERANKED_SCORE, 0.92345d));
    when(queryService.retrieveContentsForInterviewEvidence(List.of(9L), "缓存一致性"))
        .thenReturn(List.of(content, content));

    Bundle result = service.retrieveEvidence(List.of(9L), "缓存一致性");

    assertThat(result.candidates()).hasSize(1);
    assertThat(result.promptEvidence()).hasSize(1);
    assertThat(result.candidates().getFirst().id()).isEqualTo("chunk:chunk-101");
    assertThat(result.candidates().getFirst().knowledgeBaseId()).isEqualTo(9L);
    assertThat(result.candidates().getFirst().source()).isEqualTo("岗位说明.md");
    assertThat(result.candidates().getFirst().score()).isEqualTo(0.9235d);
  }
}
