package com.linrun.interview.modules.knowledgebase.rag;

import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("混合内容聚合器测试")
class InterviewHybridContentAggregatorTest {

    @Test
    @DisplayName("无 rerank 时结构化结果应置顶，非结构化结果继续 RRF 去重")
    void aggregateWithoutRerank() {
        InterviewHybridContentAggregator aggregator = new InterviewHybridContentAggregator(null);
        Query query = new Query("平均分和 JVM 知识点");
        Content sql = ContentUtil.markAsSkipRerank(content("sql", "平均分 80"));
        Content es1 = content("c1", "JVM GC");
        Content es1Again = content("c1", "JVM GC duplicate");
        Content es2 = content("c2", "线程池");

        List<Content> result = aggregator.aggregate(Map.of(
            query, List.of(List.of(sql, es1, es2), List.of(es1Again))
        ));

        assertThat(result).hasSize(3);
        assertThat(result.getFirst().textSegment().text()).isEqualTo("平均分 80");
        assertThat(result.stream()
            .filter(content -> "c1".equals(content.textSegment().metadata()
                .getString(MetadataKeyConstant.CHUNK_ID)))
            .count()).isEqualTo(1);
    }

    private Content content(String chunkId, String text) {
        return Content.from(new TextSegment(text, Metadata.from(MetadataKeyConstant.CHUNK_ID, chunkId)));
    }
}
