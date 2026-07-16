package com.linrun.interview.modules.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linrun.interview.modules.github.client.GithubPublicApiClient;
import com.linrun.interview.modules.github.client.GithubPublicApiClient.RepositoryDescriptor;
import com.linrun.interview.modules.github.client.GithubPublicApiClient.RepositoryTree;
import com.linrun.interview.modules.github.client.GithubPublicApiClient.TreeEntry;
import com.linrun.interview.modules.github.config.GithubEvidenceProperties;
import com.linrun.interview.modules.github.dto.BindGithubRepositoryRequest;
import com.linrun.interview.modules.github.dto.BindGithubRepositoryRequest.ContributionDeclaration;
import com.linrun.interview.modules.github.model.GithubFileStatus;
import com.linrun.interview.modules.github.model.GithubRepositoryEntity;
import com.linrun.interview.modules.github.model.GithubRepositoryFileEntity;
import com.linrun.interview.modules.github.security.GithubFilePolicy;
import com.linrun.interview.modules.github.security.GithubRepositoryUrlPolicy;
import com.linrun.interview.modules.github.security.GithubSecretDetector;
import com.linrun.interview.modules.knowledgebase.service.EvidenceSnapshotService;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("GitHub 仓库绑定与同步前清单")
class GithubRepositoryServiceTest {

  private static final String COMMIT = "a".repeat(40);
  private static final String BLOB = "b".repeat(40);
  private GithubPublicApiClient apiClient;
  private GithubRepositoryPersistenceService persistenceService;
  private GithubRepositoryService service;

  @BeforeEach
  void setUp() {
    GithubEvidenceProperties properties = new GithubEvidenceProperties();
    apiClient = mock(GithubPublicApiClient.class);
    persistenceService = mock(GithubRepositoryPersistenceService.class);
    GithubEvidenceIndexer indexer = mock(GithubEvidenceIndexer.class);
    EvidenceSnapshotService evidenceSnapshotService = mock(EvidenceSnapshotService.class);
    service = new GithubRepositoryService(
        properties,
        new GithubRepositoryUrlPolicy(),
        apiClient,
        new GithubFilePolicy(properties, new GithubSecretDetector()),
        persistenceService,
        indexer,
        evidenceSnapshotService);
  }

  @Test
  @DisplayName("绑定时固定默认分支 SHA，并先保存可见的安全文件清单")
  @SuppressWarnings("unchecked")
  void shouldPinCommitAndPersistPreviewManifest() {
    AtomicReference<List<GithubRepositoryFileEntity>> savedManifest = new AtomicReference<>();
    when(apiClient.getPublicRepository("demo", "repo")).thenReturn(
        new RepositoryDescriptor("demo", "repo", "https://github.com/demo/repo",
            "main", 42L, false));
    when(apiClient.resolveCommitSha("demo", "repo", "main")).thenReturn(COMMIT);
    when(apiClient.getTree("demo", "repo", COMMIT)).thenReturn(new RepositoryTree(List.of(
        new TreeEntry("README.md", "blob", BLOB, 100),
        new TreeEntry("backend/src/App.java", "blob", "c".repeat(40), 200),
        new TreeEntry("frontend/src/App.tsx", "blob", "d".repeat(40), 300),
        new TreeEntry(".env", "blob", "e".repeat(40), 20),
        new TreeEntry("node_modules/pkg.js", "blob", "f".repeat(40), 20)), false));
    when(persistenceService.createOrRefreshBinding(any(), any(), anyList()))
        .thenAnswer(invocation -> {
          GithubRepositoryEntity repository = invocation.getArgument(0);
          savedManifest.set(invocation.getArgument(2));
          repository.setId(10L);
          repository.setSyncStatus(
              com.linrun.interview.modules.github.model.GithubRepositorySyncStatus.AWAITING_SELECTION);
          repository.setSyncedFileCount(0);
          repository.setSyncedBytes(0L);
          repository.setSourceAvailable(true);
          repository.setCoreModulesJson("[\"backend\"]");
          return repository;
        });
    when(persistenceService.requireRepository(7L, 10L)).thenAnswer(invocation -> {
      GithubRepositoryEntity repository = GithubRepositoryEntity.builder()
          .id(10L).userId(7L).ownerName("demo").repositoryName("repo")
          .repositoryUrl("https://github.com/demo/repo").defaultBranch("main")
          .fixedCommitSha(COMMIT).sourceSizeKb(42L)
          .syncStatus(com.linrun.interview.modules.github.model.GithubRepositorySyncStatus.AWAITING_SELECTION)
          .syncedFileCount(0).syncedBytes(0L).sourceAvailable(true)
          .coreModulesJson("[\"backend\"]").build();
      return repository;
    });
    ArgumentCaptor<List<GithubRepositoryFileEntity>> manifestCaptor = ArgumentCaptor.forClass(List.class);
    when(persistenceService.listFiles(7L, 10L)).thenAnswer(invocation -> savedManifest.get());
    when(persistenceService.parseCoreModules(any())).thenReturn(List.of("backend"));

    BindGithubRepositoryRequest request = new BindGithubRepositoryRequest(
        "https://github.com/demo/repo",
        new ContributionDeclaration(
            List.of("backend"), "负责后端", "选择显式状态机", "修复重复消费"));
    service.bind(7L, request);

    verify(apiClient).resolveCommitSha("demo", "repo", "main");
    verify(persistenceService).createOrRefreshBinding(any(), any(), manifestCaptor.capture());
    List<GithubRepositoryFileEntity> manifest = manifestCaptor.getValue();
    assertThat(manifest).filteredOn(file -> file.getPath().equals("backend/src/App.java"))
        .allSatisfy(file -> {
          assertThat(file.getCommitSha()).isEqualTo(COMMIT);
          assertThat(file.getDefaultIncluded()).isTrue();
        });
    assertThat(manifest).filteredOn(file -> file.getPath().equals("frontend/src/App.tsx"))
        .allSatisfy(file -> assertThat(file.getDefaultIncluded()).isFalse());
    assertThat(manifest).filteredOn(file -> file.getPath().equals(".env"))
        .allSatisfy(file -> assertThat(file.getStatus())
            .isEqualTo(GithubFileStatus.EXCLUDED_SENSITIVE_PATH));
    assertThat(manifest).filteredOn(file -> file.getPath().startsWith("node_modules"))
        .allSatisfy(file -> assertThat(file.getStatus())
            .isEqualTo(GithubFileStatus.EXCLUDED_DEPENDENCY));
  }
}
