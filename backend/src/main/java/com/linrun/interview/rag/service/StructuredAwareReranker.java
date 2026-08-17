package com.linrun.interview.rag.service;

import dev.langchain4j.rag.content.Content;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化 / 非结构化检索结果拆分与合并（对齐 {@link HybridContentAggregator} 语义）。
 */
final class StructuredAwareReranker {

  record Partition(List<Content> structured, List<Content> unstructured) {
  }

  private StructuredAwareReranker() {
  }

  static Partition partition(List<Content> contents) {
    List<Content> structured = new ArrayList<>();
    List<Content> unstructured = new ArrayList<>();
    if (contents != null) {
      for (Content content : contents) {
        if (RagContentUtil.isSkipRerank(content)) {
          structured.add(content);
        } else {
          unstructured.add(content);
        }
      }
    }
    return new Partition(structured, unstructured);
  }

  static List<Content> merge(Partition partition, List<Content> rerankedUnstructured) {
    List<Content> combined = new ArrayList<>(
        partition.structured().size() + rerankedUnstructured.size());
    combined.addAll(partition.structured());
    combined.addAll(rerankedUnstructured);
    return combined;
  }
}
