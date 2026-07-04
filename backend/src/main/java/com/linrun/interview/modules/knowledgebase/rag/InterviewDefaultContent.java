package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.DefaultContent;
import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;

import java.util.Map;
import java.util.Objects;

/**
 * 知识库 Content 子类（参考业界实现的 DefaultContent）。
 *
 * <p>覆写 {@code equals/hashCode} 按 {@code EMBEDDING_ID} 元数据去重，使
 * {@link InterviewReciprocalRankFuser} 在跨路融合时能正确合并同一 chunk 的多路命中，
 * 而非按 {@link DefaultContent} 默认的全字段比较导致重复。
 *
 * <p>EMBEDDING_ID 由检索器在构造 Content 时写入 metadata（取自 ES hit.id）。
 */
public class InterviewDefaultContent extends DefaultContent {

    public InterviewDefaultContent(TextSegment textSegment, Map<ContentMetadata, Object> metadata) {
        super(textSegment, metadata);
    }

    public InterviewDefaultContent(DefaultContent defaultContent) {
        super(defaultContent.textSegment(), defaultContent.metadata());
    }

    public InterviewDefaultContent(String text) {
        super(text);
    }

    public InterviewDefaultContent(TextSegment textSegment) {
        super(textSegment);
    }

    @Override
    public int hashCode() {
        return Objects.requireNonNull(identity()).hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof InterviewDefaultContent other)) {
            return false;
        }
        return Objects.equals(this.identity(), other.identity());
    }

    private String identity() {
        String embeddingId = textSegment().metadata().getString(MetadataKeyConstant.EMBEDDING_ID);
        if (embeddingId != null && !embeddingId.isBlank()) {
            return embeddingId;
        }
        String chunkId = textSegment().metadata().getString(MetadataKeyConstant.CHUNK_ID);
        if (chunkId != null && !chunkId.isBlank()) {
            return chunkId;
        }
        return textSegment().text();
    }
}
