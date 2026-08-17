package com.linrun.interview.rag.service;

import com.linrun.interview.rag.constant.MetadataKeyConstant;
import com.linrun.interview.rag.model.RagQueryTrace;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.DefaultContent;
import dev.langchain4j.rag.query.Query;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RRF 融合与 Rerank 降级")
class InterviewReRankingContentAggregatorTest {

  @Test
  @DisplayName("Rerank 返回全零时应显式保留 RRF 顺序和 TopN")
  void zeroScoresKeepRrfOrder() {
    ScoringModel scoringModel = mock(ScoringModel.class);
    when(scoringModel.scoreAll(any(), any())).thenReturn(Response.from(List.of(0.0, 0.0, 0.0)));
    InterviewReRankingContentAggregator aggregator = aggregator(scoringModel, 2, 60, 60);
    Query query = Query.from("RAG");
    Content first = content("a", "A");
    Content second = content("b", "B");
    Content third = content("c", "C");

    List<Content> result = aggregator.aggregate(Map.of(
        query, List.<List<Content>>of(List.of(first, second, third))));

    assertThat(result).extracting(value -> value.textSegment().text())
        .containsExactly("A", "B");
  }

  @Test
  @DisplayName("传入 trace 时应写入 RERANK span")
  void recordsRerankSpan() {
    ScoringModel scoringModel = mock(ScoringModel.class);
    when(scoringModel.scoreAll(any(), any())).thenReturn(Response.from(List.of(0.9, 0.2)));
    RagQueryTrace trace = new RagQueryTrace();
    InterviewReRankingContentAggregator aggregator = InterviewReRankingContentAggregator.builder()
        .scoringModel(scoringModel)
        .maxResults(2)
        .hybridRrfK(60)
        .fusionRrfK(60)
        .trace(trace)
        .build();

    aggregator.aggregate(Map.of(
        Query.from("RAG"), List.<List<Content>>of(List.of(content("a", "A"), content("b", "B")))));

    assertThat(trace.spans()).hasSize(1);
    RagQueryTrace.Span span = trace.spans().getFirst();
    assertThat(span.name()).isEqualTo(RagQueryTrace.SPAN_RERANK);
    assertThat(span.closed()).isTrue();
    assertThat(span.output()).isEqualTo("2 docs");
  }

  @Test
  @DisplayName("Rerank 返回全等非零分时也应保留 RRF 顺序")
  void equalScoresKeepRrfOrder() {
    ScoringModel scoringModel = mock(ScoringModel.class);
    when(scoringModel.scoreAll(any(), any())).thenReturn(Response.from(List.of(0.7, 0.7)));
    Query query = Query.from("RAG");

    List<Content> result = aggregator(scoringModel, 2, 60, 60).aggregate(Map.of(
        query, List.<List<Content>>of(List.of(content("a", "A"), content("b", "B")))));

    assertThat(result).extracting(value -> value.textSegment().text())
        .containsExactly("A", "B");
  }

  @Test
  @DisplayName("Rerank 全员低于 minScore 且无标题重合时应保持 RRF 顺序")
  void belowMinScoreKeepsRrfOrder() {
    ScoringModel scoringModel = mock(ScoringModel.class);
    when(scoringModel.scoreAll(any(), any())).thenReturn(Response.from(List.of(0.2, 0.1)));
    InterviewReRankingContentAggregator aggregator = InterviewReRankingContentAggregator.builder()
        .scoringModel(scoringModel)
        .minScore(0.6)
        .maxResults(2)
        .hybridRrfK(60)
        .fusionRrfK(60)
        .build();
    Query query = Query.from("事务的隔离级别有哪些?");

    List<Content> result = aggregator.aggregate(Map.of(
        query, List.<List<Content>>of(List.of(content("a", "A"), content("b", "B")))));

    assertThat(result).extracting(value -> value.textSegment().text())
        .containsExactly("A", "B");
  }

  @Test
  @DisplayName("Rerank 全员低于 minScore 时应把标题重合的块提前")
  void belowMinScorePromotesMatchingHeading() {
    ScoringModel scoringModel = mock(ScoringModel.class);
    when(scoringModel.scoreAll(any(), any())).thenReturn(Response.from(List.of(0.2, 0.2, 0.2)));
    InterviewReRankingContentAggregator aggregator = InterviewReRankingContentAggregator.builder()
        .scoringModel(scoringModel)
        .minScore(0.6)
        .maxResults(2)
        .hybridRrfK(60)
        .fusionRrfK(60)
        .build();
    Query query = Query.from("事务的隔离级别有哪些?");

    List<Content> result = aggregator.aggregate(Map.of(
        query, List.<List<Content>>of(List.of(
            content("gap", "## 间隙锁了解吗？\n仅在可重复读"),
            content("iso", "## 事务的隔离级别有哪些?\nMySQL 支持四种隔离级别"),
            content("cmd", "## 说说事务控制的命令？\nSTART TRANSACTION")))));

    assertThat(result.getFirst().textSegment().text()).contains("事务的隔离级别有哪些");
  }

  @Test
  @DisplayName("BGE logit 先 sigmoid 再按 0~1 阈值过滤，并写入归一化 RERANKED_SCORE")
  void normalizesLogitsThenDropsNearMisses() {
    ScoringModel scoringModel = mock(ScoringModel.class);
    when(scoringModel.scoreAll(any(), any())).thenReturn(Response.from(List.of(5.07, 4.15, 1.77)));
    InterviewReRankingContentAggregator aggregator = InterviewReRankingContentAggregator.builder()
        .scoringModel(scoringModel)
        .minScore(0.88)
        .maxResults(6)
        .hybridRrfK(60)
        .fusionRrfK(60)
        .build();

    List<Content> result = aggregator.aggregate(Map.of(
        Query.from("缓存穿透"), List.<List<Content>>of(List.of(
            content("code", "布隆过滤器拦截不存在的 key"),
            content("def", "缓存和数据库都没有该数据"),
            content("break", "缓存击穿是热点 key 过期")))));

    assertThat(result).extracting(value -> value.textSegment().text())
        .containsExactly("布隆过滤器拦截不存在的 key", "缓存和数据库都没有该数据");
    assertThat((Double) result.getFirst().metadata().get(ContentMetadata.RERANKED_SCORE))
        .isGreaterThan(0.99)
        .isLessThanOrEqualTo(1.0);
  }

  @Test
  @DisplayName("问缓存穿透时，标题是击穿的块应被丢掉而不是只往后排")
  void penetrationQueryDropsBreakdownHeading() {
    ScoringModel scoringModel = mock(ScoringModel.class);
    when(scoringModel.scoreAll(any(), any())).thenReturn(Response.from(List.of(0.97, 0.91)));
    InterviewReRankingContentAggregator aggregator = InterviewReRankingContentAggregator.builder()
        .scoringModel(scoringModel)
        .minScore(0.80)
        .maxResults(6)
        .hybridRrfK(60)
        .fusionRrfK(60)
        .build();

    List<Content> result = aggregator.aggregate(Map.of(
        Query.from("什么是缓存穿透，如何防止"), List.<List<Content>>of(List.of(
            content("break", "## 29. 什么是缓存击穿？\n大量请求就会穿透缓存直接访问数据库"),
            content("pen", "## 什么是缓存穿透？\n数据压根不存在，请求落到数据库")))));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().textSegment().text()).contains("什么是缓存穿透");
  }

  @Test
  @DisplayName("对比题同时问穿透和击穿时不应丢掉任何一侧")
  void contrastQueryKeepsBothHeadings() {
    ScoringModel scoringModel = mock(ScoringModel.class);
    when(scoringModel.scoreAll(any(), any())).thenReturn(Response.from(List.of(0.97, 0.91)));
    InterviewReRankingContentAggregator aggregator = InterviewReRankingContentAggregator.builder()
        .scoringModel(scoringModel)
        .minScore(0.80)
        .maxResults(6)
        .hybridRrfK(60)
        .fusionRrfK(60)
        .build();

    List<Content> result = aggregator.aggregate(Map.of(
        Query.from("缓存穿透和缓存击穿有什么区别"), List.<List<Content>>of(List.of(
            content("break", "## 29. 什么是缓存击穿？\n热点 key 过期"),
            content("pen", "## 什么是缓存穿透？\n数据压根不存在")))));

    assertThat(result).extracting(value -> value.textSegment().text())
        .anyMatch(text -> text.contains("什么是缓存击穿"))
        .anyMatch(text -> text.contains("什么是缓存穿透"));
  }

  @Test
  @DisplayName("Rerank 异常时应保留 RRF 顺序")
  void rerankExceptionKeepsRrfOrder() {
    ScoringModel scoringModel = mock(ScoringModel.class);
    when(scoringModel.scoreAll(any(), any())).thenThrow(new IllegalStateException("timeout"));
    InterviewReRankingContentAggregator aggregator = aggregator(scoringModel, 3, 60, 60);
    Query query = Query.from("RAG");

    List<Content> result = aggregator.aggregate(Map.of(
        query, List.<List<Content>>of(List.of(content("a", "A"), content("b", "B")))));

    assertThat(result).extracting(value -> value.textSegment().text())
        .containsExactly("A", "B");
  }

  @Test
  @DisplayName("hybrid RRF K 应真实影响同一 Query 的通道融合")
  void hybridRrfKChangesChannelFusion() {
    Collection<List<Content>> channels = List.of(
        List.of(content("a", "A"), content("x1", "X1"), content("x2", "X2"),
            content("x3", "X3"), content("b", "B")),
        List.of(content("y1", "Y1"), content("y2", "Y2"), content("y3", "Y3"),
            content("y4", "Y4"), content("b", "B")));
    Query query = Query.from("RAG");
    List<Content> k1 = new ExposedAggregator(1).fuseFor(query, channels);
    List<Content> k100 = new ExposedAggregator(100).fuseFor(query, channels);

    assertThat(k1.indexOf(find(k1, "A"))).isLessThan(k1.indexOf(find(k1, "B")));
    assertThat(k100.indexOf(find(k100, "B"))).isLessThan(k100.indexOf(find(k100, "A")));
  }

  @Test
  @DisplayName("fusion RRF K 与跨 Query TopK 应真实生效")
  void fusionRrfKAndFinalTopKAreUsed() {
    Map<Query, Collection<List<Content>>> input = Map.of(
        Query.from("q1"), List.<List<Content>>of(List.of(
            content("a", "A"), content("x1", "X1"), content("x2", "X2"),
            content("x3", "X3"), content("b", "B"))),
        Query.from("q2"), List.<List<Content>>of(List.of(
            content("y1", "Y1"), content("y2", "Y2"), content("y3", "Y3"),
            content("y4", "Y4"), content("b", "B"))));
    List<Content> k1 = InterviewReRankingContentAggregator.builder()
        .scoringModel(null)
        .fusionRrfK(1)
        .fusionFinalTopK(10)
        .querySelector(values -> values.keySet().iterator().next())
        .build()
        .aggregate(input);
    List<Content> k100 = InterviewReRankingContentAggregator.builder()
        .scoringModel(null)
        .fusionRrfK(100)
        .fusionFinalTopK(1)
        .querySelector(values -> values.keySet().iterator().next())
        .build()
        .aggregate(input);

    assertThat(k1.indexOf(find(k1, "A"))).isLessThan(k1.indexOf(find(k1, "B")));
    assertThat(k100).hasSize(1);
    assertThat(k100.getFirst().textSegment().text()).isEqualTo("B");
  }

  private InterviewReRankingContentAggregator aggregator(
      ScoringModel scoringModel, int maxResults, int hybridRrfK, int fusionRrfK) {
    return InterviewReRankingContentAggregator.builder()
        .scoringModel(scoringModel)
        .maxResults(maxResults)
        .hybridRrfK(hybridRrfK)
        .fusionRrfK(fusionRrfK)
        .build();
  }

  private Content content(String id, String text) {
    Metadata metadata = new Metadata()
        .put(MetadataKeyConstant.EMBEDDING_ID, id)
        .put(MetadataKeyConstant.CHUNK_ID, id);
    return new DefaultContent(new TextSegment(text, metadata));
  }

  private Content find(List<Content> contents, String text) {
    return contents.stream()
        .filter(value -> value.textSegment().text().equals(text))
        .findFirst()
        .orElseThrow();
  }

  private static final class ExposedAggregator extends InterviewReRankingContentAggregator {

    private ExposedAggregator(int hybridRrfK) {
      super(null, null, null, 10, null, null, hybridRrfK, 60, 10);
    }

    private List<Content> fuseFor(Query query, Collection<List<Content>> channels) {
      return fuse(Map.of(query, channels)).get(query);
    }
  }
}
