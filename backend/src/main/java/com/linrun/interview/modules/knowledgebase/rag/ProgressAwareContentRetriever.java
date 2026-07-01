package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 带进度通知的检索器装饰器（对齐 know-engine ProgressAwareContentRetriever）。
 */
public class ProgressAwareContentRetriever implements ContentRetriever {

  public enum Kind {
    ES("正在检索知识库..."),
    SQL("正在检索数据库..."),
    NEO4J("正在检索知识图谱..."),
    GENERIC("正在检索文档...");

    private final String message;

    Kind(String message) {
      this.message = message;
    }

    public String message() {
      return message;
    }
  }

  private final ContentRetriever delegate;
  private final Consumer<String> progressCallback;
  private final Kind kind;
  private final AtomicBoolean progressSent = new AtomicBoolean(false);

  public ProgressAwareContentRetriever(ContentRetriever delegate,
                                       Consumer<String> progressCallback,
                                       Kind kind) {
    this.delegate = delegate;
    this.progressCallback = progressCallback;
    this.kind = kind != null ? kind : Kind.GENERIC;
  }

  public static ContentRetriever wrap(ContentRetriever delegate,
                                      Consumer<String> progressCallback,
                                      Kind kind) {
    if (progressCallback == null) {
      return delegate;
    }
    return new ProgressAwareContentRetriever(delegate, progressCallback, kind);
  }

  @Override
  public List<Content> retrieve(Query query) {
    if (progressCallback != null && progressSent.compareAndSet(false, true)) {
      progressCallback.accept(kind.message());
    }
    return delegate.retrieve(query);
  }

  public ContentRetriever getDelegate() {
    return delegate;
  }
}
