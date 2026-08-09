package com.linrun.interview.rag.service;import com.linrun.interview.rag.service.ContextExpansionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.rag.constant.MetadataKeyConstant;
import com.linrun.interview.document.entity.KnowledgeBaseSegmentEntity;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import com.linrun.interview.document.service.KnowledgeSegmentService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RAG 精排后上下文扩展")
class ContextExpandingContentAggregatorTest {

  @Test
  @DisplayName("先由聚合器选出子块，再替换成同范围父块")
  void shouldExpandOnlyAfterDelegateRanking() {
    KnowledgeSegmentService segmentService = mock(KnowledgeSegmentService.class);
    KnowledgeBaseQueryProperties.ParentExpand properties = new KnowledgeBaseQueryProperties.ParentExpand();
    properties.setEnabled(true);
    properties.setStrategy("replace");
    properties.setMaxChars(1200);

    Content child = Content.from(TextSegment.from("精确命中的子块", metadata("child-1", "parent-1")));
    Content discarded = Content.from(TextSegment.from("未进入 TopN 的子块", metadata("child-2", null)));
    ContentAggregator delegate = input -> List.of(child);

    KnowledgeBaseSegmentEntity parent = parent("parent-1", "完整父章节");
    when(segmentService.findByChunkIdIn(List.of("parent-1"))).thenReturn(List.of(parent));

    ContextExpandingContentAggregator aggregator = new ContextExpandingContentAggregator(
        delegate, new ContextExpansionService(segmentService, properties), 1200);
    Map<Query, Collection<List<Content>>> input = Map.of(
        Query.from("问题"), List.of(List.of(child, discarded)));

    List<Content> result = aggregator.aggregate(input);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().textSegment().text()).isEqualTo("完整父章节");
  }

  @Test
  @DisplayName("同一父章节的多个子块命中只保留一份扩展上下文")
  void shouldDeduplicateChildrenExpandedToSameParent() {
    KnowledgeSegmentService segmentService = mock(KnowledgeSegmentService.class);
    KnowledgeBaseQueryProperties.ParentExpand properties = new KnowledgeBaseQueryProperties.ParentExpand();
    properties.setEnabled(true);
    properties.setStrategy("replace");
    properties.setMaxChars(1200);
    Content firstChild = Content.from(TextSegment.from(
        "子块一", metadata("child-1", "parent-1")));
    Content secondChild = Content.from(TextSegment.from(
        "子块二", metadata("child-2", "parent-1")));
    KnowledgeBaseSegmentEntity parent = parent("parent-1", "同一完整父章节");
    when(segmentService.findByChunkIdIn(List.of("parent-1"))).thenReturn(List.of(parent));

    List<Content> result = new ContextExpansionService(segmentService, properties)
        .expand(List.of(firstChild, secondChild));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().textSegment().text()).isEqualTo("同一完整父章节");
  }

  @Test
  @DisplayName("append 策略下同一父章节只追加一次")
  void appendStrategyAddsSharedParentOnlyOnce() {
    KnowledgeSegmentService segmentService = mock(KnowledgeSegmentService.class);
    KnowledgeBaseQueryProperties.ParentExpand properties = new KnowledgeBaseQueryProperties.ParentExpand();
    properties.setEnabled(true);
    properties.setStrategy("append");
    properties.setMaxChars(1200);
    Content firstChild = Content.from(TextSegment.from(
        "子块一", metadata("child-1", "parent-1")));
    Content secondChild = Content.from(TextSegment.from(
        "子块二", metadata("child-2", "parent-1")));
    KnowledgeBaseSegmentEntity parent = parent("parent-1", "共享父章节");
    when(segmentService.findByChunkIdIn(List.of("parent-1"))).thenReturn(List.of(parent));

    List<Content> result = new ContextExpansionService(segmentService, properties)
        .expand(List.of(firstChild, secondChild));

    assertThat(result).extracting(content -> content.textSegment().text())
        .containsExactly("子块一\n\n共享父章节", "子块二");
  }

  @Test
  @DisplayName("扩展后应按排序前缀限制全局上下文字符预算")
  void shouldLimitExpandedContextByTotalChars() {
    KnowledgeSegmentService segmentService = mock(KnowledgeSegmentService.class);
    KnowledgeBaseQueryProperties.ParentExpand properties = new KnowledgeBaseQueryProperties.ParentExpand();
    properties.setEnabled(false);
    Content first = Content.from(TextSegment.from("12345"));
    Content second = Content.from(TextSegment.from("67890"));
    Content third = Content.from(TextSegment.from("abc"));
    ContentAggregator delegate = input -> List.of(first, second, third);
    ContextExpandingContentAggregator aggregator = new ContextExpandingContentAggregator(
        delegate, new ContextExpansionService(segmentService, properties), 9);

    List<Content> result = aggregator.aggregate(Map.of(
        Query.from("问题"), List.of(List.of(first, second, third))));

    assertThat(result).hasSize(2);
    assertThat(result.getFirst()).isEqualTo(first);
    assertThat(result.get(1).textSegment().text()).isEqualTo("6789");
    assertThat(result).extracting(value -> value.textSegment().text().length())
        .containsExactly(5, 4);
  }

  private Metadata metadata(String evidenceId, String parentId) {
    Metadata metadata = new Metadata()
        .put(MetadataKeyConstant.OWNER_USER_ID, "7")
        .put(MetadataKeyConstant.DATA_DOMAIN, DataDomain.CANDIDATE.name())
        .put(MetadataKeyConstant.RESOURCE_ID, "9")
        .put(MetadataKeyConstant.RESOURCE_VERSION, "3")
        .put(MetadataKeyConstant.EVIDENCE_ID, evidenceId);
    return parentId == null
        ? metadata
        : metadata.put(MetadataKeyConstant.PARENT_CHUNK_ID, parentId);
  }

  private KnowledgeBaseSegmentEntity parent(String chunkId, String text) {
    KnowledgeBaseSegmentEntity segment = new KnowledgeBaseSegmentEntity();
    segment.setChunkId(chunkId);
    segment.setText(text);
    segment.setUserId(7L);
    segment.setDataDomain(DataDomain.CANDIDATE);
    segment.setResourceId("9");
    segment.setResourceVersion("3");
    return segment;
  }
}
