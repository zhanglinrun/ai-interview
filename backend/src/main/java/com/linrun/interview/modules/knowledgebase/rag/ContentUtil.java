package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;

public final class ContentUtil {

    public static final String SKIP_RERANK = "skipRerank";

    private ContentUtil() {
    }

    public static Content markAsSkipRerank(Content content) {
        Metadata metadata = content.textSegment().metadata().copy().put(SKIP_RERANK, "true");
        return Content.from(new TextSegment(content.textSegment().text(), metadata), content.metadata());
    }

    public static boolean isSkipRerank(Content content) {
        return content != null && "true".equals(content.textSegment().metadata().getString(SKIP_RERANK));
    }
}
