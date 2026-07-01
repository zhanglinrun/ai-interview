package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 带进度通知的内容聚合器装饰器（对齐 know-engine ProgressAwareContentAggregator）。
 */
public class ProgressAwareContentAggregator implements ContentAggregator {

  private static final String MSG_RERANK = "正在排序筛选结果...";
  private static final String MSG_GENERATE = "正在生成回答...";

  private final ContentAggregator delegate;
  private final Consumer<String> progressCallback;
  private final boolean emitGenerateProgress;
  private final AtomicBoolean rerankProgressSent = new AtomicBoolean(false);

  public ProgressAwareContentAggregator(ContentAggregator delegate,
                                        Consumer<String> progressCallback) {
    this(delegate, progressCallback, true);
  }

  public ProgressAwareContentAggregator(ContentAggregator delegate,
                                        Consumer<String> progressCallback,
                                        boolean emitGenerateProgress) {
    this.delegate = delegate;
    this.progressCallback = progressCallback;
    this.emitGenerateProgress = emitGenerateProgress;
  }

  public static ContentAggregator wrap(ContentAggregator delegate,
                                       Consumer<String> progressCallback) {
    if (delegate == null) {
      return null;
    }
    if (progressCallback == null) {
      return delegate;
    }
    return new ProgressAwareContentAggregator(delegate, progressCallback);
  }

  @Override
  public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
    if (progressCallback != null && rerankProgressSent.compareAndSet(false, true)) {
      progressCallback.accept(MSG_RERANK);
    }
    List<Content> results = delegate.aggregate(queryToContents);
    if (emitGenerateProgress && progressCallback != null) {
      progressCallback.accept(MSG_GENERATE);
    }
    return results;
  }
}
