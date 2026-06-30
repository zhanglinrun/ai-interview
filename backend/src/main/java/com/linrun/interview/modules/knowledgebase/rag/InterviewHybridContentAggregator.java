package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.DefaultContent;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReciprocalRankFuser;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多源聚合：SQL 结构化结果直接放前面，ES 文本结果继续 RRF/rerank。
 */
public class InterviewHybridContentAggregator implements ContentAggregator {

    private final ContentAggregator unstructuredAggregator;

    public InterviewHybridContentAggregator(ContentAggregator unstructuredAggregator) {
        this.unstructuredAggregator = unstructuredAggregator;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        if (queryToContents == null || queryToContents.isEmpty()) {
            return List.of();
        }

        List<Content> structured = new ArrayList<>();
        Map<Query, Collection<List<Content>>> unstructuredByQuery = new LinkedHashMap<>();
        for (Map.Entry<Query, Collection<List<Content>>> entry : queryToContents.entrySet()) {
            List<List<Content>> unstructuredLists = new ArrayList<>();
            for (List<Content> contents : entry.getValue()) {
                List<Content> unstructured = new ArrayList<>();
                for (Content content : contents) {
                    if (ContentUtil.isSkipRerank(content)) {
                        structured.add(content);
                    } else {
                        unstructured.add(content);
                    }
                }
                if (!unstructured.isEmpty()) {
                    unstructuredLists.add(unstructured);
                }
            }
            if (!unstructuredLists.isEmpty()) {
                unstructuredByQuery.put(entry.getKey(), unstructuredLists);
            }
        }

        List<Content> unstructured = aggregateUnstructured(unstructuredByQuery);
        List<Content> combined = new ArrayList<>(structured.size() + unstructured.size());
        combined.addAll(structured);
        combined.addAll(unstructured);
        return combined;
    }

    private List<Content> aggregateUnstructured(Map<Query, Collection<List<Content>>> unstructuredByQuery) {
        if (unstructuredByQuery.isEmpty()) {
            return List.of();
        }
        if (unstructuredAggregator != null) {
            return unstructuredAggregator.aggregate(unstructuredByQuery);
        }
        List<List<InterviewDefaultContent>> fusedByQuery = new ArrayList<>();
        for (Collection<List<Content>> contents : unstructuredByQuery.values()) {
            fusedByQuery.add(ReciprocalRankFuser.fuse(contents).stream()
                .map(this::toDefaultContent)
                .toList());
        }
        return InterviewReciprocalRankFuser.fuse(fusedByQuery);
    }

    private InterviewDefaultContent toDefaultContent(Content content) {
        if (content instanceof DefaultContent defaultContent) {
            return new InterviewDefaultContent(defaultContent);
        }
        return new InterviewDefaultContent(content.textSegment(), content.metadata());
    }
}
