package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.modules.knowledgebase.model.RagSourceDTO;
import com.linrun.interview.modules.knowledgebase.rag.RagQueryTrace;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@DisplayName("知识库流式引用终态")
class KnowledgeBaseQueryServiceCitationTest {

  @Test
  @DisplayName("仅标记有效引用且保持来源元数据不变")
  void marksOnlyValidCitationsWithoutRebuildingSources() {
    List<RagSourceDTO> sources = List.of(
        source(1L, "RAG.md", "检索片段", 0.91),
        source(2L, "Agent.md", "编排片段", 0.82));
    CitationAnalyzer.CitationAnalysis citation =
        new CitationAnalyzer(0.5, 0.1).analyze("回答引用第二条 [2]，越界编号 [9]", 2);

    KnowledgeBaseQueryService.StreamCitationResult result =
        KnowledgeBaseQueryService.buildStreamCitationResult(sources, citation, 0.73);

    assertThat(result.sources()).hasSize(2);
    assertThat(result.sources()).extracting(RagSourceDTO::cited).containsExactly(false, true);
    assertThat(result.sources().get(1))
        .usingRecursiveComparison()
        .ignoringFields("cited")
        .isEqualTo(sources.get(1));
    assertThat(result.confidence()).isEqualTo(0.73);
    assertThat(result.invalidCitations()).containsExactly(9);
    assertThat(result.groundedStatus()).isEqualTo("need_escalate");
  }

  @Test
  @DisplayName("高置信且有有效引用时 grounded 为 pass")
  void resolvesPassWhenCitedAndConfident() {
    List<RagSourceDTO> sources = List.of(source(1L, "RAG.md", "检索片段", 0.91));
    CitationAnalyzer.CitationAnalysis citation =
        new CitationAnalyzer(0.5, 0.1).analyze("回答引用 [1]", 1);

    KnowledgeBaseQueryService.StreamCitationResult result =
        KnowledgeBaseQueryService.buildStreamCitationResult(sources, citation, 0.8);

    assertThat(result.groundedStatus()).isEqualTo("pass");
  }

  @Test
  @DisplayName("正常完成与取消竞争时 Trace 只保存一次且参数一致")
  void savesTraceOnceWithCitationResult() {
    RagQueryTraceService traceService = mock(RagQueryTraceService.class);
    RagQueryTrace trace = new RagQueryTrace();
    KnowledgeBaseQueryService.StreamCitationResult result =
        new KnowledgeBaseQueryService.StreamCitationResult(
            List.of(source(1L, "RAG.md", "检索片段", 0.91)), 0.73, List.of(9), "need_escalate");
    AtomicBoolean saved = new AtomicBoolean(false);

    KnowledgeBaseQueryService.saveStreamTraceOnce(
        saved, traceService, 7L, List.of(1L), "问题", trace, result, "回答 [1] [9]", 321L);
    KnowledgeBaseQueryService.saveStreamTraceOnce(
        saved, traceService, 7L, List.of(1L), "问题", trace, result, "重复保存", 999L);

    verify(traceService).save(
        7L, List.of(1L), "问题", trace, result.sources(), "回答 [1] [9]",
        0.73, List.of(9), 321L);
    verifyNoMoreInteractions(traceService);
  }

  private RagSourceDTO source(Long knowledgeBaseId, String title, String snippet, Double similarity) {
    return new RagSourceDTO(
        knowledgeBaseId,
        title,
        title,
        "TECH",
        null,
        null,
        null,
        snippet,
        similarity,
        false);
  }
}
