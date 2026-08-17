package com.linrun.interview.rag.service;

import com.linrun.interview.rag.constant.MetadataKeyConstant;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;

/**
 * RAG 内容元数据工具。
 *
 * <p>SQL/Cypher 等结构化检索结果标记 {@link MetadataKeyConstant#SKIP_RERANK} 后，
 * {@link HybridContentAggregator} 直接透传，不参与 RRF 融合与 BGE rerank。</p>
 */
public final class RagContentUtil {

  private RagContentUtil() {
  }

  public static Content markAsSkipRerank(Content content) {
    TextSegment originalSegment = content.textSegment();
    Metadata metadata = originalSegment.metadata() != null
        ? Metadata.from(originalSegment.metadata().toMap())
        : new Metadata();
    metadata.put(MetadataKeyConstant.SKIP_RERANK, "true");
    return Content.from(TextSegment.from(originalSegment.text(), metadata), content.metadata());
  }

  public static boolean isSkipRerank(Content content) {
    if (content == null || content.textSegment() == null || content.textSegment().metadata() == null) {
      return false;
    }
    Object flag = content.textSegment().metadata().toMap().get(MetadataKeyConstant.SKIP_RERANK);
    if (flag == null) {
      return false;
    }
    String value = String.valueOf(flag).trim();
    return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
  }
}
