package com.linrun.interview.github.service;import com.linrun.interview.rag.service.RerankService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.rag.model.EvidenceMetadata;
import com.linrun.interview.github.config.GithubEvidenceProperties;
import com.linrun.interview.github.dto.GithubEvidenceCardDTO;
import com.linrun.interview.github.dto.GithubEvidenceCardRequest;
import com.linrun.interview.github.dto.GithubEvidenceCardRequest.CapabilityTarget;
import com.linrun.interview.github.model.GithubCodeEvidenceEntity;
import com.linrun.interview.github.model.GithubRepositoryEntity;
import com.linrun.interview.github.model.GithubRepositorySyncStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("岗位能力 × GitHub 代码证据卡")
class GithubEvidenceCardServiceTest {

  private static final String SHA = "a".repeat(40);
  private GithubRepositoryPersistenceService persistenceService;
  private GithubEvidenceIndexer evidenceIndexer;
  private GithubEvidenceCardService service;
  private GithubRepositoryEntity repository;

  @BeforeEach
  void setUp() {
    persistenceService = mock(GithubRepositoryPersistenceService.class);
    evidenceIndexer = mock(GithubEvidenceIndexer.class);
    service = new GithubEvidenceCardService(
        new GithubEvidenceProperties(), persistenceService, evidenceIndexer);
    repository = GithubRepositoryEntity.builder()
        .id(9L).userId(7L).fixedCommitSha(SHA)
        .syncStatus(GithubRepositorySyncStatus.SYNCED).build();
    when(persistenceService.requireRepository(7L, 9L)).thenReturn(repository);
  }

  @Test
  @DisplayName("真实符号匹配生成自然且可追溯的项目深挖题")
  void shouldGenerateEvidenceGroundedQuestion() {
    GithubCodeEvidenceEntity first = chunk(
        "src/RetrievalService.java", "hybridSearch", 10, 30,
        "public Result hybridSearch(Query query) { return rerank(query); }");
    GithubCodeEvidenceEntity second = chunk(
        "src/RerankService.java", "rerank", 20, 42,
        "public Result rerank(Query query) { return result; }");
    when(persistenceService.listEvidence(7L, 9L, SHA, 2000))
        .thenReturn(List.of(first, second));
    when(evidenceIndexer.metadata(repository, first)).thenReturn(metadata(first));
    when(evidenceIndexer.metadata(repository, second)).thenReturn(metadata(second));

    List<GithubEvidenceCardDTO> cards = service.generate(
        7L,
        9L,
        new GithubEvidenceCardRequest(List.of(new CapabilityTarget(
            "RAG_RETRIEVAL", "1.0", "混合检索与重排", List.of("retrieval", "rerank"))), 2));

    assertThat(cards).singleElement().satisfies(card -> {
      assertThat(card.evidenceStatus()).isEqualTo("SUFFICIENT");
      assertThat(card.interviewQuestion())
          .containsAnyOf("src/RetrievalService.java", "src/RerankService.java")
          .containsAnyOf("hybridSearch", "rerank")
          .doesNotContain("固定提交", "体现“", "设计决策、取舍");
      assertThat(card.evidenceRefs()).hasSize(2)
          .allSatisfy(ref -> assertThat(ref.dataDomain()).isEqualTo(DataDomain.GITHUB));
      assertThat(card.neutralNote()).doesNotContain("造假");
    });
  }

  @Test
  @DisplayName("无直接代码证据时标记 NONE 且明确不据此扣分")
  void shouldNotPenalizeMissingEvidence() {
    when(persistenceService.listEvidence(7L, 9L, SHA, 2000)).thenReturn(List.of());

    GithubEvidenceCardDTO card = service.generate(
        7L,
        9L,
        new GithubEvidenceCardRequest(List.of(new CapabilityTarget(
            "MQ_RELIABILITY", "1.0", "消息可靠性", List.of("rabbitmq"))), 2)).getFirst();

    assertThat(card.evidenceStatus()).isEqualTo("NONE");
    assertThat(card.interviewQuestion()).isNull();
    assertThat(card.neutralNote()).contains("不据此扣分");
  }

  private GithubCodeEvidenceEntity chunk(
      String path,
      String symbol,
      int start,
      int end,
      String content
  ) {
    return GithubCodeEvidenceEntity.builder()
        .ownerUserId(7L).dataDomain(DataDomain.GITHUB)
        .resourceId("github-repository:9").resourceVersion(SHA)
        .repositoryId(9L).commitSha(SHA).path(path).language("Java")
        .symbolName(symbol).symbolKind("METHOD").startLine(start).endLine(end)
        .parentSummary(path + " " + symbol).content(content)
        .contentHash(GithubHashing.sha256(content))
        .evidenceId("gh-" + GithubHashing.sha256(path).substring(0, 40))
        .sourceLocator("https://github.com/demo/repo/blob/" + SHA + "/" + path
            + "#L" + start + "-L" + end)
        .build();
  }

  private EvidenceMetadata metadata(GithubCodeEvidenceEntity chunk) {
    return new EvidenceMetadata(
        7L,
        DataDomain.GITHUB,
        "github-repository:9",
        SHA,
        chunk.getEvidenceId(),
        chunk.getContentHash(),
        "GITHUB_CODE",
        chunk.getSourceLocator());
  }
}
