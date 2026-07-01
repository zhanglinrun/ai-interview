package com.linrun.interview.modules.knowledgebase.rag;

import com.linrun.interview.common.security.UserContext;
import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jText2CypherRetriever;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Neo4j Text2Cypher 检索器，空结果或异常时降级到知识库检索。
 */
@Slf4j
public class InterviewNeo4jContentRetriever implements ContentRetriever {

    private final Neo4jText2CypherRetriever neo4jText2CypherRetriever;
    private final ContentRetriever fallbackRetriever;

    public InterviewNeo4jContentRetriever(Neo4jText2CypherRetriever neo4jText2CypherRetriever,
                                          ContentRetriever fallbackRetriever) {
        this.neo4jText2CypherRetriever = neo4jText2CypherRetriever;
        this.fallbackRetriever = fallbackRetriever;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> results;
        try {
            Long userId = UserContext.getUserId();
            String enriched = "我的问题是：" + query.text()
                + (userId != null ? ", 我的用户Id是: " + userId : "")
                + ", 现在是：" + LocalDateTime.now();
            query = new Query(enriched, query.metadata());
            results = neo4jText2CypherRetriever.retrieve(query);
        } catch (Exception e) {
            log.warn("Neo4j 检索异常，降级知识库检索, query={}", query.text(), e);
            return fallbackRetriever.retrieve(query);
        }
        if (results == null || results.isEmpty() || isCypherResultEmpty(results)) {
            log.info("Neo4j 检索结果为空，降级知识库检索, query={}", query.text());
            return fallbackRetriever.retrieve(query);
        }
        return results.stream().map(ContentUtil::markAsSkipRerank).collect(Collectors.toList());
    }

    private boolean isCypherResultEmpty(List<Content> results) {
        if (results.size() != 1) {
            return false;
        }
        String text = results.get(0).textSegment().text();
        if (!text.startsWith("Result of executing '")) {
            return false;
        }
        int columnStartIndex = text.indexOf(":\n");
        if (columnStartIndex == -1) {
            return false;
        }
        int dataStartIndex = text.indexOf('\n', columnStartIndex + 2);
        return dataStartIndex == -1 || text.substring(dataStartIndex + 1).trim().isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jGraph graph;
        private PromptTemplate promptTemplate;
        private List<String> examples;
        private List<String> relationships;
        private String dialect;
        private int maxRetries = 1;
        private ChatModel chatModel;
        private ContentRetriever fallbackRetriever;

        public Builder graph(dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jGraph graph) {
            this.graph = graph;
            return this;
        }

        public Builder promptTemplate(PromptTemplate promptTemplate) {
            this.promptTemplate = promptTemplate;
            return this;
        }

        public Builder examples(List<String> examples) {
            this.examples = examples;
            return this;
        }

        public Builder relationships(List<String> relationships) {
            this.relationships = relationships;
            return this;
        }

        public Builder dialect(String dialect) {
            this.dialect = dialect;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder fallbackRetriever(ContentRetriever fallbackRetriever) {
            this.fallbackRetriever = fallbackRetriever;
            return this;
        }

        public InterviewNeo4jContentRetriever build() {
            Neo4jText2CypherRetriever.Builder neo4jBuilder = Neo4jText2CypherRetriever.builder()
                .graph(graph)
                .chatModel(chatModel)
                .maxRetries(maxRetries);
            if (promptTemplate != null) {
                neo4jBuilder.promptTemplate(promptTemplate);
            }
            if (examples != null) {
                neo4jBuilder.examples(examples);
            }
            if (relationships != null) {
                neo4jBuilder.relationships(relationships);
            }
            if (dialect != null) {
                neo4jBuilder.dialect(dialect);
            }
            return new InterviewNeo4jContentRetriever(neo4jBuilder.build(), fallbackRetriever);
        }
    }
}
