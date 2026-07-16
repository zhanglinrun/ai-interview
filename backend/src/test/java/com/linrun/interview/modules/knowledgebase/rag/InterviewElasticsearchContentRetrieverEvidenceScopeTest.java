package com.linrun.interview.modules.knowledgebase.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.evidence.DataDomain;
import com.linrun.interview.common.evidence.EvidenceScope;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Elasticsearch 证据范围硬过滤")
class InterviewElasticsearchContentRetrieverEvidenceScopeTest {

  @Test
  @DisplayName("BM25 请求下推 owner/domain/resource 且结果层拒绝跨用户片段")
  void pushesScopeIntoEsQueryAndRejectsCrossUserHit() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] response = searchResponse().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(response);
      }
    });
    server.start();

    EvidenceScope scope = new EvidenceScope(
        7L,
        List.of(new EvidenceScope.DomainScope(
            DataDomain.CANDIDATE, Set.of("doc-1"), Set.of("v1"), 1.0d)),
        true);
    KnowledgeBaseQueryProperties.ParentExpand expand = new KnowledgeBaseQueryProperties.ParentExpand();
    expand.setEnabled(false);
    try (RestClient restClient = RestClient.builder(
        new HttpHost("127.0.0.1", server.getAddress().getPort(), "http")).build()) {
      InterviewElasticsearchContentRetriever retriever =
          new InterviewElasticsearchContentRetriever(
              mock(ElasticsearchEmbeddingStore.class),
              mock(EmbeddingModel.class),
              10,
              0.0d,
              List.of(),
              null,
              expand,
              new KnowledgeBaseQueryProperties.Hybrid(),
              null,
              null,
              restClient,
              "evidence-index",
              new ObjectMapper(),
              "full_text",
              7L,
              null,
              scope);

      var result = retriever.retrieve(Query.from("缓存一致性"));

      assertThat(result).hasSize(1);
      assertThat(result.getFirst().textSegment().text()).isEqualTo("owned evidence");
      assertThat(requestBody.get())
          .contains("metadata.ownerUserId.keyword", "metadata.dataDomain.keyword",
              "metadata.resourceId.keyword", "metadata.resourceVersion.keyword")
          .contains("\"value\":\"7\"", "CANDIDATE", "doc-1", "v1");
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("fresh ES 索引不存在时检索返回空结果")
  void returnsEmptyResultWhenIndexDoesNotExist() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      byte[] response = """
          {"error":{"type":"index_not_found_exception","reason":"no such index"},"status":404}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(404, response.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(response);
      }
    });
    server.start();

    KnowledgeBaseQueryProperties.ParentExpand expand = new KnowledgeBaseQueryProperties.ParentExpand();
    expand.setEnabled(false);
    try (RestClient restClient = RestClient.builder(
        new HttpHost("127.0.0.1", server.getAddress().getPort(), "http")).build()) {
      InterviewElasticsearchContentRetriever retriever =
          new InterviewElasticsearchContentRetriever(
              mock(ElasticsearchEmbeddingStore.class),
              mock(EmbeddingModel.class),
              10,
              0.0d,
              List.of(),
              null,
              expand,
              new KnowledgeBaseQueryProperties.Hybrid(),
              null,
              null,
              restClient,
              "missing-index",
              new ObjectMapper(),
              "full_text",
              7L,
              null,
              new EvidenceScope(7L, List.of(new EvidenceScope.DomainScope(
                  DataDomain.CANDIDATE, Set.of("doc-1"), Set.of("v1"), 1.0d)), true));

      assertThat(retriever.retrieve(Query.from("缓存一致性"))).isEmpty();
    } finally {
      server.stop(0);
    }
  }

  private String searchResponse() {
    return """
        {"hits":{"hits":[
          {"_id":"foreign","_score":1.0,"_source":{"text":"foreign evidence","metadata":{
            "ownerUserId":"8","dataDomain":"CANDIDATE","resourceId":"doc-1",
            "resourceVersion":"v1","evidenceId":"e-foreign","contentHash":"h1",
            "sourceType":"TEST","sourceLocator":"foreign"}}},
          {"_id":"owned","_score":0.9,"_source":{"text":"owned evidence","metadata":{
            "ownerUserId":"7","dataDomain":"CANDIDATE","resourceId":"doc-1",
            "resourceVersion":"v1","evidenceId":"e-owned","contentHash":"h2",
            "sourceType":"TEST","sourceLocator":"owned"}}}
        ]}}
        """;
  }
}
