package com.linrun.interview.modules.knowledgebase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.ai.PromptTemplate;
import com.linrun.interview.common.evidence.DataDomain;
import com.linrun.interview.common.evidence.EvidenceCandidate;
import com.linrun.interview.common.evidence.EvidencePacket;
import com.linrun.interview.common.evidence.EvidenceRef;
import com.linrun.interview.common.evidence.EvidenceScope;
import com.linrun.interview.common.evidence.EvidenceStatus;
import com.linrun.interview.modules.knowledgebase.config.ElasticSearchProperties;
import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;
import com.linrun.interview.modules.knowledgebase.rag.CorrectiveRetrievalGrader;
import com.linrun.interview.modules.knowledgebase.rag.ContextExpandingContentAggregator;
import com.linrun.interview.modules.knowledgebase.rag.ContextExpansionService;
import com.linrun.interview.modules.knowledgebase.rag.InterviewElasticsearchContentRetriever;
import com.linrun.interview.modules.knowledgebase.rag.InterviewQueryDecomposer;
import com.linrun.interview.modules.knowledgebase.rag.RagQueryTrace;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RestClient;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试工作流使用的分域证据检索入口。
 *
 * <p>实时追问只做各域混合召回、域权重 RRF、Rerank 与上下文扩展；面试准备额外启用 Query
 * Decomposition 和 CRAG，且纠正重检索最多一次。整个服务只使用显式 dataUserId，不读取
 * UserContext。
 */
@Slf4j
@Service
public class EvidenceRetrievalService {

  private static final int SNAPSHOT_TEXT_MAX_CHARS = 1200;

  private final ElasticsearchEmbeddingStore embeddingStore;
  private final LlmProviderRegistry llmProviderRegistry;
  private final RestClient restClient;
  private final ElasticSearchProperties elasticSearchProperties;
  private final KnowledgeSegmentService segmentService;
  private final KnowledgeBaseQueryProperties queryProperties;
  private final RerankService rerankService;
  private final ObjectMapper objectMapper;
  private final ContextExpansionService contextExpansionService;
  private final PromptTemplate decomposePrompt;
  private final PromptTemplate cragPrompt;

  public EvidenceRetrievalService(
      ElasticsearchEmbeddingStore embeddingStore,
      LlmProviderRegistry llmProviderRegistry,
      RestClient restClient,
      ElasticSearchProperties elasticSearchProperties,
      KnowledgeSegmentService segmentService,
      KnowledgeBaseQueryProperties queryProperties,
      RerankService rerankService,
      ObjectMapper objectMapper,
      ResourceLoader resourceLoader
  ) throws IOException {
    this.embeddingStore = embeddingStore;
    this.llmProviderRegistry = llmProviderRegistry;
    this.restClient = restClient;
    this.elasticSearchProperties = elasticSearchProperties;
    this.segmentService = segmentService;
    this.queryProperties = queryProperties;
    this.rerankService = rerankService;
    this.objectMapper = objectMapper;
    this.contextExpansionService = new ContextExpansionService(
        segmentService, queryProperties.getParentExpand());
    this.decomposePrompt = new PromptTemplate(resourceLoader
        .getResource(queryProperties.getDecomposePromptPath())
        .getContentAsString(StandardCharsets.UTF_8));
    this.cragPrompt = new PromptTemplate(resourceLoader
        .getResource(queryProperties.getCragPromptPath())
        .getContentAsString(StandardCharsets.UTF_8));
  }

  /** 实时面试路径：不产生 Query Decomposition / CRAG 的额外 LLM 调用。 */
  public EvidencePacket retrieveRealtime(
      EvidenceScope scope,
      String capabilityAtomKey,
      String query
  ) {
    requireQuery(query);
    List<String> degraded = new ArrayList<>();
    List<Content> contents = expand(retrieveAndRank(scope, List.of(query), degraded));
    EvidenceStatus status = contents.isEmpty()
        ? EvidenceStatus.NONE
        : contents.size() >= 2 ? EvidenceStatus.SUFFICIENT : EvidenceStatus.WEAK;
    return toPacket(capabilityAtomKey, query, status, contents, List.of(), degraded);
  }

  /**
   * 面试前准备路径：复杂问题按需分解，检索后做 CRAG；AMBIGUOUS 只纠正重检索一次。
   */
  public EvidencePacket prepareEvidence(
      EvidenceScope scope,
      String capabilityAtomKey,
      String query
  ) {
    requireQuery(query);
    List<String> degraded = new ArrayList<>();
    List<String> queries = decompose(scope, query, degraded);
    List<Content> contents = retrieveAndRank(scope, queries, degraded);
    if (contents.isEmpty()) {
      return toPacket(capabilityAtomKey, query, EvidenceStatus.NONE,
          List.of(), List.of(), degraded);
    }

    CorrectiveRetrievalGrader.GradeResult grade = grade(scope, query, contents, degraded);
    if (grade.degraded()) {
      return toPacket(capabilityAtomKey, query, EvidenceStatus.WEAK,
          expand(contents), List.of(), degraded);
    }
    return switch (grade.grade()) {
      case CORRECT -> toPacket(capabilityAtomKey, query, EvidenceStatus.SUFFICIENT,
          expand(contents), List.of(), degraded);
      case INCORRECT -> toPacket(capabilityAtomKey, query, EvidenceStatus.NONE,
          List.of(), List.of(), degraded);
      case AMBIGUOUS -> correctOnce(
          scope, capabilityAtomKey, query, grade.correctedQuery(), contents, degraded);
    };
  }

  private List<String> decompose(
      EvidenceScope scope,
      String query,
      List<String> degraded
  ) {
    if (!queryProperties.getDecompose().isEnabled()) {
      return List.of(query);
    }
    try {
      RagQueryTrace trace = new RagQueryTrace();
      InterviewQueryDecomposer decomposer = new InterviewQueryDecomposer(
          original -> List.of(original),
          llmProviderRegistry.getUserChatModel(scope.dataUserId()),
          decomposePrompt,
          queryProperties.getDecompose().getMaxSubQueries(),
          null,
          trace);
      return decomposer.transform(Query.from(query)).stream().map(Query::text).distinct().toList();
    } catch (Exception e) {
      degraded.add("QUERY_DECOMPOSITION_UNAVAILABLE");
      log.warn("证据准备查询分解失败，使用原问题: error={}", e.getMessage(), e);
      return List.of(query);
    }
  }

  private CorrectiveRetrievalGrader.GradeResult grade(
      EvidenceScope scope,
      String query,
      List<Content> contents,
      List<String> degraded
  ) {
    try {
      CorrectiveRetrievalGrader grader = new CorrectiveRetrievalGrader(
          llmProviderRegistry.getUserChatModel(scope.dataUserId()),
          cragPrompt,
          queryProperties.getCrag().getGradeTopN(),
          queryProperties.getCrag().getSnippetMaxChars());
      CorrectiveRetrievalGrader.GradeResult result = grader.grade(query, contents);
      if (result.degraded()) {
        degraded.add("CRAG_UNAVAILABLE");
      }
      return result;
    } catch (Exception e) {
      degraded.add("CRAG_UNAVAILABLE");
      log.warn("证据准备 CRAG 失败，证据标记 WEAK: error={}", e.getMessage(), e);
      return new CorrectiveRetrievalGrader.GradeResult(
          CorrectiveRetrievalGrader.Grade.CORRECT, "CRAG unavailable", "", true);
    }
  }

  private EvidencePacket correctOnce(
      EvidenceScope scope,
      String capabilityAtomKey,
      String originalQuery,
      String correctedQuery,
      List<Content> first,
      List<String> degraded
  ) {
    if (correctedQuery == null || correctedQuery.isBlank()
        || correctedQuery.equals(originalQuery)) {
      return toPacket(capabilityAtomKey, originalQuery, EvidenceStatus.WEAK,
          expand(first), List.of(), degraded);
    }
    degraded.add("CRAG_CORRECTED_ONCE");
    List<Content> corrected = retrieveAndRank(scope, List.of(correctedQuery), degraded);
    List<Content> merged = fuseRankedLists(
        List.of(first, corrected),
        List.of(1.0d, 1.0d),
        queryProperties.getFusion().getRrfK(),
        queryProperties.getFusion().getFinalTopK());
    merged = rerank(merged, originalQuery, degraded);
    EvidenceStatus status = merged.isEmpty() ? EvidenceStatus.NONE : EvidenceStatus.WEAK;
    return toPacket(capabilityAtomKey, originalQuery, status, expand(merged), List.of(), degraded);
  }

  private List<Content> retrieveAndRank(
      EvidenceScope scope,
      List<String> queries,
      List<String> degraded
  ) {
    List<List<Content>> queryResults = new ArrayList<>();
    for (String query : queries) {
      List<List<Content>> domainResults = new ArrayList<>();
      List<Double> domainWeights = new ArrayList<>();
      for (EvidenceScope.DomainScope domain : scope.domains()) {
        EvidenceScope singleDomain = scope.only(domain.domain());
        InterviewElasticsearchContentRetriever retriever = createRetriever(singleDomain);
        domainResults.add(retriever.retrieve(Query.from(query)));
        domainWeights.add(domain.weight());
      }
      queryResults.add(fuseRankedLists(
          domainResults,
          domainWeights,
          queryProperties.getHybrid().getRrfK(),
          queryProperties.getHybrid().getFusionTopK()));
    }
    List<Double> queryWeights = queryResults.stream().map(ignored -> 1.0d).toList();
    List<Content> fused = fuseRankedLists(
        queryResults,
        queryWeights,
        queryProperties.getFusion().getRrfK(),
        queryProperties.getFusion().getFinalTopK());
    return rerank(fused, queries.getFirst(), degraded);
  }

  private InterviewElasticsearchContentRetriever createRetriever(EvidenceScope scope) {
    return new InterviewElasticsearchContentRetriever(
        embeddingStore,
        llmProviderRegistry.getDefaultEmbeddingModel(),
        Math.max(queryProperties.getSearch().getTopkMedium(), 1),
        queryProperties.getSearch().getMinScoreDefault(),
        List.of(),
        queryProperties.getHybrid(),
        null,
        null,
        restClient,
        elasticSearchProperties.getIndexName(),
        objectMapper,
        null,
        scope.dataUserId(),
        scope, elasticSearchProperties.getDimensions());
  }

  private List<Content> expand(List<Content> contents) {
    return ContextExpandingContentAggregator.limitByTotalChars(
        contextExpansionService.expand(contents),
        queryProperties.getContext().getMaxTotalChars());
  }

  private List<Content> rerank(
      List<Content> contents,
      String query,
      List<String> degraded
  ) {
    if (contents.isEmpty()) {
      return contents;
    }
    if (!queryProperties.getRerank().isEnabled() || !rerankService.isEnabled()) {
      degraded.add("RERANK_UNAVAILABLE");
      return contents;
    }
    Response<List<Double>> response = rerankService.scoreAll(
        contents.stream().map(Content::textSegment).toList(), query);
    List<Double> scores = response.content();
    if (scores == null || scores.size() != contents.size()) {
      degraded.add("RERANK_INVALID_RESPONSE");
      return contents;
    }
    List<ScoredContent> scored = new ArrayList<>();
    for (int i = 0; i < contents.size(); i++) {
      scored.add(new ScoredContent(withRerankScore(contents.get(i), scores.get(i)), scores.get(i)));
    }
    scored.sort(Comparator.comparingDouble(ScoredContent::score).reversed());
    int limit = Math.min(
        Math.max(queryProperties.getRerank().getTopN(), 1), scored.size());
    return scored.subList(0, limit).stream().map(ScoredContent::content).toList();
  }

  private Content withRerankScore(Content content, double score) {
    Metadata metadata = content.textSegment().metadata().copy()
        .put(ContentMetadata.RERANKED_SCORE.name(), score);
    Map<ContentMetadata, Object> contentMetadata = new HashMap<>(content.metadata());
    contentMetadata.put(ContentMetadata.RERANKED_SCORE, score);
    return Content.from(new TextSegment(content.textSegment().text(), metadata), contentMetadata);
  }

  static List<Content> fuseRankedLists(
      List<List<Content>> rankedLists,
      List<Double> weights,
      int rrfK,
      int limit
  ) {
    if (rankedLists.size() != weights.size()) {
      throw new IllegalArgumentException("rankedLists 与 weights 数量不一致");
    }
    Map<String, FusedContent> byEvidenceId = new LinkedHashMap<>();
    for (int listIndex = 0; listIndex < rankedLists.size(); listIndex++) {
      List<Content> ranked = rankedLists.get(listIndex);
      double weight = weights.get(listIndex);
      for (int rank = 0; rank < ranked.size(); rank++) {
        Content content = ranked.get(rank);
        String evidenceId = content.textSegment().metadata()
            .getString(MetadataKeyConstant.EVIDENCE_ID);
        if (evidenceId == null || evidenceId.isBlank()) {
          continue;
        }
        double increment = weight / (Math.max(rrfK, 1) + rank + 1.0d);
        double domainWeight = readDomainWeight(content, weight);
        byEvidenceId.compute(evidenceId, (ignored, current) -> current == null
            ? new FusedContent(content, increment, domainWeight)
            : new FusedContent(
                current.content(), current.score() + increment, current.domainWeight()));
      }
    }
    return byEvidenceId.values().stream()
        .sorted(Comparator.comparingDouble(FusedContent::score).reversed())
        .limit(Math.max(limit, 1))
        .map(item -> withRetrievalScore(
            item.content(), item.score(), item.domainWeight()))
        .toList();
  }

  private static Content withRetrievalScore(
      Content content,
      double score,
      double domainWeight
  ) {
    Metadata metadata = content.textSegment().metadata().copy()
        .put(ContentMetadata.SCORE.name(), score)
        .put(MetadataKeyConstant.DOMAIN_WEIGHT, String.valueOf(domainWeight));
    Map<ContentMetadata, Object> contentMetadata = new HashMap<>(content.metadata());
    contentMetadata.put(ContentMetadata.SCORE, score);
    return Content.from(new TextSegment(content.textSegment().text(), metadata), contentMetadata);
  }

  private static double readDomainWeight(Content content, double fallback) {
    String raw = content.textSegment().metadata()
        .getString(MetadataKeyConstant.DOMAIN_WEIGHT);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      double parsed = Double.parseDouble(raw);
      return Double.isFinite(parsed) && parsed > 0.0d ? parsed : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private EvidencePacket toPacket(
      String capabilityAtomKey,
      String query,
      EvidenceStatus status,
      List<Content> contents,
      List<String> conflicts,
      List<String> degraded
  ) {
    List<EvidenceCandidate> candidates = new ArrayList<>();
    for (int i = 0; i < contents.size(); i++) {
      Content content = contents.get(i);
      EvidenceRef ref = toEvidenceRef(content.textSegment().metadata());
      if (ref == null) {
        continue;
      }
      candidates.add(new EvidenceCandidate(
          ref,
          truncate(content.textSegment().text()),
          score(content, ContentMetadata.SCORE),
          score(content, ContentMetadata.RERANKED_SCORE),
          readDomainWeight(content, 1.0d),
          i + 1));
    }
    EvidenceStatus resolvedStatus = candidates.isEmpty() ? EvidenceStatus.NONE : status;
    return new EvidencePacket(
        capabilityAtomKey,
        query,
        resolvedStatus,
        candidates,
        conflicts,
        degraded.stream().distinct().toList());
  }

  private EvidenceRef toEvidenceRef(Metadata metadata) {
    try {
      return new EvidenceRef(
          metadata.getString(MetadataKeyConstant.EVIDENCE_ID),
          DataDomain.valueOf(metadata.getString(MetadataKeyConstant.DATA_DOMAIN)),
          metadata.getString(MetadataKeyConstant.RESOURCE_ID),
          metadata.getString(MetadataKeyConstant.RESOURCE_VERSION),
          metadata.getString(MetadataKeyConstant.SOURCE_TYPE),
          metadata.getString(MetadataKeyConstant.SOURCE_LOCATOR),
          metadata.getString(MetadataKeyConstant.CONTENT_HASH));
    } catch (Exception e) {
      log.warn("跳过缺失强制元数据的检索片段: evidenceId={}",
          metadata.getString(MetadataKeyConstant.EVIDENCE_ID));
      return null;
    }
  }

  private Double score(Content content, ContentMetadata key) {
    Object value = content.metadata().get(key);
    return value instanceof Number number ? number.doubleValue() : null;
  }

  private String truncate(String text) {
    if (text == null) {
      return "";
    }
    return text.length() <= SNAPSHOT_TEXT_MAX_CHARS
        ? text : text.substring(0, SNAPSHOT_TEXT_MAX_CHARS) + "...";
  }

  private void requireQuery(String query) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query 不能为空");
    }
  }

  private record FusedContent(Content content, double score, double domainWeight) {}

  private record ScoredContent(Content content, double score) {}
}
