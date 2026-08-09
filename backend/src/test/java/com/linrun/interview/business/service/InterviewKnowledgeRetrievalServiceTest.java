package com.linrun.interview.business.service;

import com.linrun.interview.ai.service.PromptSanitizer;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.vo.InterviewEvidence;
import com.linrun.interview.business.vo.InterviewEvidence.Bundle;
import com.linrun.interview.rag.constant.MetadataKeyConstant;
import com.linrun.interview.rag.service.KnowledgeBaseQueryService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("面试结构化证据检索测试")
class InterviewKnowledgeRetrievalServiceTest {

  @AfterEach
  void clearUserContext() {
    UserContext.clear();
  }

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

  @Test
  @DisplayName("异步线程无 UserContext 时仍使用显式数据用户检索")
  void usesExplicitDataUserWithoutThreadContext() {
    KnowledgeBaseQueryService queryService = mock(KnowledgeBaseQueryService.class);
    InterviewKnowledgeRetrievalService service = new InterviewKnowledgeRetrievalService(
        queryService, mock(PromptSanitizer.class));
    when(queryService.retrieveContentsForInterviewEvidence(
        7L, List.of(9L), "缓存一致性"))
        .thenReturn(List.of());

    UserContext.clear();
    service.retrieveEvidence(7L, List.of(9L), "缓存一致性");

    verify(queryService).retrieveContentsForInterviewEvidence(
        7L, List.of(9L), "缓存一致性");
    verify(queryService, never()).retrieveContentsForInterviewEvidence(
        List.of(9L), "缓存一致性");
  }

  @Test
  @DisplayName("显式数据用户不被其他请求线程身份覆盖")
  void explicitDataUserCannotBeOverriddenByThreadContext() {
    KnowledgeBaseQueryService queryService = mock(KnowledgeBaseQueryService.class);
    InterviewKnowledgeRetrievalService service = new InterviewKnowledgeRetrievalService(
        queryService, mock(PromptSanitizer.class));
    when(queryService.retrieveContentsForInterviewEvidence(
        7L, List.of(9L), "缓存一致性"))
        .thenReturn(List.of());

    UserContext.setUserId(99L);
    service.retrieveEvidence(7L, List.of(9L), "缓存一致性");

    verify(queryService).retrieveContentsForInterviewEvidence(
        7L, List.of(9L), "缓存一致性");
    verify(queryService, never()).retrieveContentsForInterviewEvidence(
        99L, List.of(9L), "缓存一致性");
  }

  @Test
  @DisplayName("最终面试证据 Prompt 应受总字符预算约束")
  void limitsFinalEvidencePromptLength() {
    PromptSanitizer promptSanitizer = mock(PromptSanitizer.class);
    when(promptSanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    when(promptSanitizer.wrapWithDelimiters(eq("interview_evidence"), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(1));
    InterviewKnowledgeRetrievalService service = new InterviewKnowledgeRetrievalService(
        mock(KnowledgeBaseQueryService.class), promptSanitizer);
    InterviewEvidence evidence = new InterviewEvidence(
        "e-1", 9L, "chunk-1", "embedding-1", "s".repeat(4000),
        "Java", 0.9d, "x".repeat(600));
    Bundle bundle = new Bundle("query", List.of(evidence), List.of(evidence));

    String prompt = service.buildEvidencePrompt(bundle);

    assertThat(prompt).hasSizeLessThanOrEqualTo(3000).endsWith("…");
  }
}
