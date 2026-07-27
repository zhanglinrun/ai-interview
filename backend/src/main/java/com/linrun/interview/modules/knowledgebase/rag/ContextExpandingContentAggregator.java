package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 在召回融合和 rerank 完成后执行 small-to-big 上下文扩展。 */
public class ContextExpandingContentAggregator implements ContentAggregator {

  private final ContentAggregator delegate;
  private final ContextExpansionService expansionService;
  private final int maxTotalChars;
  private final Consumer<String> progressCallback;

  public ContextExpandingContentAggregator(
      ContentAggregator delegate,
      ContextExpansionService expansionService,
      int maxTotalChars
  ) {
    this(delegate, expansionService, maxTotalChars, null);
  }

  public ContextExpandingContentAggregator(
      ContentAggregator delegate,
      ContextExpansionService expansionService,
      int maxTotalChars,
      Consumer<String> progressCallback
  ) {
    this.delegate = delegate;
    this.expansionService = expansionService;
    this.maxTotalChars = maxTotalChars;
    this.progressCallback = progressCallback;
  }

  @Override
  public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
    List<Content> aggregated = delegate.aggregate(queryToContents);
    if (progressCallback != null && aggregated != null && !aggregated.isEmpty()) {
      progressCallback.accept("正在扩展上下文...");
    }
    return limitByTotalChars(expansionService.expand(aggregated));
  }

  public static List<Content> limitByTotalChars(List<Content> contents, int maxTotalChars) {
    if (contents == null || contents.isEmpty() || maxTotalChars < 1) {
      return contents == null ? List.of() : contents;
    }
    int used = 0;
    java.util.ArrayList<Content> limited = new java.util.ArrayList<>();
    for (Content content : contents) {
      String text = content.textSegment().text();
      int chars = text == null ? 0 : text.length();
      int remaining = maxTotalChars - used;
      if (remaining <= 0) {
        break;
      }
      if (chars <= remaining) {
        limited.add(content);
        used += chars;
        continue;
      }
      String truncated = text == null ? "" : text.substring(0, remaining);
      limited.add(Content.from(
          new dev.langchain4j.data.segment.TextSegment(
              truncated, content.textSegment().metadata().put("contextTruncated", "1")),
          content.metadata()));
      break;
    }
    return List.copyOf(limited);
  }

  private List<Content> limitByTotalChars(List<Content> contents) {
    return limitByTotalChars(contents, maxTotalChars);
  }
}
