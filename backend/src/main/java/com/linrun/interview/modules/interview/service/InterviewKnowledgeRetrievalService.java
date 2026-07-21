package com.linrun.interview.modules.interview.service;

import com.linrun.interview.common.ai.PromptSanitizer;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.interview.agent.model.InterviewEvidence;
import com.linrun.interview.modules.interview.agent.model.InterviewEvidence.Bundle;
import com.linrun.interview.modules.interview.topic.InterviewTopic;
import com.linrun.interview.modules.interview.topic.InterviewTopic.Category;
import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 面试出题的知识库检索桥接：把 RAG 结果保留为带稳定 ID、来源和分数的结构化证据，
 * 再按需渲染成受 Prompt 注入边界保护的文本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewKnowledgeRetrievalService {

  private static final int MAX_CANDIDATES = 6;
  private static final int MAX_PROMPT_EVIDENCE = 4;
  private static final int MAX_CHARS_PER_CHUNK = 600;
  private static final int MAX_PROMPT_CHARS = 3000;
  private static final int MAX_QUERY_CATEGORIES = 4;

  private final KnowledgeBaseQueryService knowledgeBaseQueryService;
  private final PromptSanitizer promptSanitizer;

  /**
   * Planner 使用的宽查询入口。底层同样走逐轮轻量检索通道，不触发生成式查询改写。
   */
  public String buildKbReferenceSection(List<Long> knowledgeBaseIds, InterviewTopic topic) {
    if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || topic == null) {
      return "";
    }
    return buildKbReferenceSection(retrieveEvidence(knowledgeBaseIds, buildQuery(topic)));
  }

  /** 异步 Planner 使用持久化的数据用户身份检索，禁止回退消费线程的 UserContext。 */
  public String buildKbReferenceSection(Long dataUserId, List<Long> knowledgeBaseIds,
                                        InterviewTopic topic) {
    if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || topic == null) {
      return "";
    }
    requireDataUserId(dataUserId);
    return buildKbReferenceSection(
        retrieveEvidence(dataUserId, knowledgeBaseIds, buildQuery(topic)));
  }

  private String buildKbReferenceSection(Bundle bundle) {
    if (bundle.promptEvidence().isEmpty()) {
      return "";
    }
    return "以下是岗位关联知识库中检索到的资料要点。规划时优先覆盖有证据支撑的核心知识点：\n"
        + buildEvidencePrompt(bundle);
  }

  /**
   * 返回检索候选与送入 Interviewer 的证据子集。候选顺序沿用 RRF/rerank 最终顺序，
   * promptEvidence 是候选的有界 Top-N；模型最终声明使用的 ID 会由编排器再次校验。
   */
  public Bundle retrieveEvidence(List<Long> knowledgeBaseIds, String query) {
    if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()
        || query == null || query.isBlank()) {
      return Bundle.empty(query);
    }
    return retrieveEvidence(knowledgeBaseIds, query, () -> knowledgeBaseQueryService
        .retrieveContentsForInterviewEvidence(knowledgeBaseIds, query));
  }

  /** 异步入口：dataUserId 是数据权限身份，不从线程上下文推断。 */
  public Bundle retrieveEvidence(Long dataUserId, List<Long> knowledgeBaseIds, String query) {
    if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()
        || query == null || query.isBlank()) {
      return Bundle.empty(query);
    }
    requireDataUserId(dataUserId);
    return retrieveEvidence(knowledgeBaseIds, query, () -> knowledgeBaseQueryService
        .retrieveContentsForInterviewEvidence(dataUserId, knowledgeBaseIds, query));
  }

  private Bundle retrieveEvidence(List<Long> knowledgeBaseIds, String query,
                                  Supplier<List<Content>> retriever) {
    try {
      List<Content> contents = retriever.get();
      Map<String, InterviewEvidence> unique = new LinkedHashMap<>();
      contents.stream()
          .map(this::toEvidence)
          .limit(MAX_CANDIDATES * 2L)
          .forEach(evidence -> unique.putIfAbsent(evidence.id(), evidence));
      List<InterviewEvidence> candidates = unique.values().stream()
          .limit(MAX_CANDIDATES)
          .toList();
      List<InterviewEvidence> promptEvidence = candidates.stream()
          .limit(MAX_PROMPT_EVIDENCE)
          .toList();
      log.info("面试证据检索完成: kbIds={}, query={}, candidates={}, selected={}",
          knowledgeBaseIds, query, candidates.stream().map(InterviewEvidence::id).toList(),
          promptEvidence.stream().map(InterviewEvidence::id).toList());
      return new Bundle(query, candidates, promptEvidence);
    } catch (Exception e) {
      log.warn("面试证据检索失败，降级为无知识库证据: kbIds={}, query={}",
          knowledgeBaseIds, query, e);
      return Bundle.empty(query);
    }
  }

  private void requireDataUserId(Long dataUserId) {
    if (dataUserId == null) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "面试证据检索缺少数据用户身份");
    }
  }

  /** 将结构化证据渲染成带来源边界的 Interviewer 上下文。 */
  public String buildEvidencePrompt(Bundle bundle) {
    if (bundle == null || bundle.promptEvidence().isEmpty()) {
      return "";
    }
    String body = bundle.promptEvidence().stream()
        .map(this::formatEvidence)
        .collect(Collectors.joining("\n"));
    String sanitized = promptSanitizer.sanitize(body);
    String bounded = sanitized.length() <= MAX_PROMPT_CHARS
        ? sanitized
        : sanitized.substring(0, MAX_PROMPT_CHARS - 1) + "…";
    return promptSanitizer.wrapWithDelimiters(
        "interview_evidence", bounded);
  }

  private InterviewEvidence toEvidence(Content content) {
    TextSegment segment = content.textSegment();
    var metadata = segment.metadata();
    String chunkId = metadata.getString(MetadataKeyConstant.CHUNK_ID);
    String embeddingId = metadata.getString(MetadataKeyConstant.EMBEDDING_ID);
    Long knowledgeBaseId = parseLong(metadata.getString(MetadataKeyConstant.DOC_ID));
    String source = firstNonBlank(
        metadata.getString(MetadataKeyConstant.FILE_NAME),
        knowledgeBaseId == null ? null : "doc-" + knowledgeBaseId,
        "知识库");
    String category = metadata.getString(MetadataKeyConstant.CATEGORY);
    String snippet = truncate(segment.text());
    String evidenceId = stableEvidenceId(chunkId, embeddingId, knowledgeBaseId, snippet);
    return new InterviewEvidence(
        evidenceId,
        knowledgeBaseId,
        chunkId,
        embeddingId,
        source,
        category,
        extractScore(content),
        snippet);
  }

  private String formatEvidence(InterviewEvidence evidence) {
    String score = evidence.score() == null
        ? "n/a" : String.format(Locale.ROOT, "%.4f", evidence.score());
    return "- [evidence_id=" + evidence.id()
        + "; source=" + evidence.source()
        + "; score=" + score + "] " + evidence.snippet();
  }

  private String buildQuery(InterviewTopic topic) {
    String categories = topic.categories() == null ? "" : topic.categories().stream()
        .limit(MAX_QUERY_CATEGORIES)
        .map(Category::label)
        .collect(Collectors.joining(" "));
    return (topic.name() + " " + categories + " 核心知识点 面试考点").strip();
  }

  private Double extractScore(Content content) {
    Double reranked = finiteNumber(content.metadata().get(ContentMetadata.RERANKED_SCORE));
    if (reranked != null) {
      return reranked;
    }
    return finiteNumber(content.metadata().get(ContentMetadata.SCORE));
  }

  private Double finiteNumber(Object value) {
    if (!(value instanceof Number number)) {
      return null;
    }
    double score = number.doubleValue();
    if (Double.isNaN(score) || Double.isInfinite(score)) {
      return null;
    }
    return Math.round(score * 10000.0d) / 10000.0d;
  }

  private String stableEvidenceId(String chunkId, String embeddingId,
                                  Long knowledgeBaseId, String snippet) {
    if (chunkId != null && !chunkId.isBlank()) {
      return "chunk:" + chunkId;
    }
    if (embeddingId != null && !embeddingId.isBlank()) {
      return "embedding:" + embeddingId;
    }
    String scope = knowledgeBaseId == null ? "unknown" : String.valueOf(knowledgeBaseId);
    return "content:" + scope + ":" + Integer.toUnsignedString(snippet.hashCode(), 36);
  }

  private Long parseLong(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private String truncate(String text) {
    if (text == null) {
      return "";
    }
    String normalized = text.strip();
    return normalized.length() <= MAX_CHARS_PER_CHUNK
        ? normalized
        : normalized.substring(0, MAX_CHARS_PER_CHUNK) + "…";
  }
}
