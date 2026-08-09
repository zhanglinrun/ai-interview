package com.linrun.interview.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.github.service.GithubCodeChunker;
import com.linrun.interview.github.client.GithubPublicApiClient;
import com.linrun.interview.github.client.GithubPublicApiClient.BlobContent;
import com.linrun.interview.github.config.GithubEvidenceProperties;
import com.linrun.interview.github.dto.GithubSyncResultDTO;
import com.linrun.interview.github.dto.SyncGithubRepositoryRequest;
import com.linrun.interview.github.model.GithubCodeEvidenceEntity;
import com.linrun.interview.github.model.GithubFileKind;
import com.linrun.interview.github.model.GithubFileStatus;
import com.linrun.interview.github.model.GithubRepositoryEntity;
import com.linrun.interview.github.model.GithubRepositoryFileEntity;
import com.linrun.interview.github.model.GithubRepositorySyncStatus;
import com.linrun.interview.github.security.GithubContentInspector;
import com.linrun.interview.github.security.GithubSecretDetector;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("GitHub 固定 SHA 受限同步")
class GithubRepositorySyncServiceTest {

  private static final String COMMIT = "a".repeat(40);
  private static final String SAFE_BLOB = "b".repeat(40);
  private static final String SECRET_BLOB = "c".repeat(40);
  private GithubEvidenceProperties properties;
  private GithubPublicApiClient apiClient;
  private GithubRepositoryPersistenceService persistenceService;
  private GithubEvidenceIndexer evidenceIndexer;
  private GithubRepositorySyncService service;
  private GithubRepositoryEntity repository;
  private List<GithubRepositoryFileEntity> manifest;

  @BeforeEach
  void setUp() {
    properties = new GithubEvidenceProperties();
    apiClient = mock(GithubPublicApiClient.class);
    persistenceService = mock(GithubRepositoryPersistenceService.class);
    evidenceIndexer = mock(GithubEvidenceIndexer.class);
    GithubSecretDetector detector = new GithubSecretDetector();
    service = new GithubRepositorySyncService(
        properties,
        apiClient,
        new GithubContentInspector(detector),
        new GithubCodeChunker(properties),
        persistenceService,
        evidenceIndexer);
    repository = GithubRepositoryEntity.builder()
        .id(9L).userId(7L).ownerName("demo").repositoryName("repo")
        .repositoryUrl("https://github.com/demo/repo")
        .fixedCommitSha(COMMIT).syncStatus(GithubRepositorySyncStatus.AWAITING_SELECTION)
        .syncedFileCount(0).syncedBytes(0L).build();
    manifest = new ArrayList<>(List.of(
        file("src/main/java/demo/App.java", SAFE_BLOB),
        file("src/main/resources/application.yml", SECRET_BLOB)));
    when(persistenceService.requireRepository(7L, 9L)).thenReturn(repository);
    when(persistenceService.listFiles(7L, 9L)).thenReturn(manifest);
    when(apiClient.getBlob("demo", "repo", SAFE_BLOB, properties.getMaxFileBytes()))
        .thenReturn(blob(SAFE_BLOB, """
            public class App {
              public String run() {
                return "ok";
              }
            }
            """.stripTrailing()));
    when(apiClient.getBlob("demo", "repo", SECRET_BLOB, properties.getMaxFileBytes()))
        .thenReturn(blob(SECRET_BLOB, "api_key = \"secret-value-1234567890\""));
    when(evidenceIndexer.replace(eq(repository), anyList()))
        .thenAnswer(invocation -> {
          List<?> chunks = invocation.getArgument(1);
          return java.util.stream.IntStream.range(0, chunks.size())
              .mapToObj(index -> "embedding-" + index).toList();
        });
  }

  @Test
  @DisplayName("安全文件按符号行号切块，Secret 正文不落库，结果标记 PARTIAL")
  @SuppressWarnings("unchecked")
  void shouldSyncSafeEvidenceAndBlockSecrets() {
    GithubSyncResultDTO result = service.sync(
        7L,
        9L,
        new SyncGithubRepositoryRequest(
            COMMIT,
            manifest.stream().map(GithubRepositoryFileEntity::getPath).toList(),
            List.of()));

    assertThat(result.status()).isEqualTo(GithubRepositorySyncStatus.PARTIAL);
    assertThat(result.syncedFiles()).isEqualTo(1);
    assertThat(result.blockedFiles()).isEqualTo(1);
    assertThat(manifest.get(1).getStatus()).isEqualTo(GithubFileStatus.SECRET_BLOCKED);
    assertThat(manifest.get(1).getContentSnapshot()).isNull();

    ArgumentCaptor<List<GithubCodeEvidenceEntity>> evidenceCaptor = ArgumentCaptor.forClass(List.class);
    verify(persistenceService).replaceSnapshot(
        eq(repository), eq(manifest), evidenceCaptor.capture(), any(), eq(1), any(Long.class), eq(1));
    assertThat(evidenceCaptor.getValue()).isNotEmpty().allSatisfy(evidence -> {
      assertThat(evidence.getCommitSha()).isEqualTo(COMMIT);
      assertThat(evidence.getOwnerUserId()).isEqualTo(7L);
      assertThat(evidence.getDataDomain()).isEqualTo(DataDomain.GITHUB);
      assertThat(evidence.getResourceId()).isEqualTo("github-repository:9");
      assertThat(evidence.getResourceVersion()).isEqualTo(COMMIT);
      assertThat(evidence.getPath()).isEqualTo("src/main/java/demo/App.java");
      assertThat(evidence.getStartLine()).isPositive();
      assertThat(evidence.getEndLine()).isGreaterThanOrEqualTo(evidence.getStartLine());
      assertThat(evidence.getSourceLocator()).contains(COMMIT).contains("#L");
      assertThat(evidence.getContent()).doesNotContain("secret-value");
    });
  }

  @Test
  @DisplayName("页面 SHA 与绑定 SHA 不一致时在任何外部调用前拒绝")
  void shouldRejectStaleExpectedSha() {
    assertThatThrownBy(() -> service.sync(
        7L, 9L, new SyncGithubRepositoryRequest("f".repeat(40), List.of(), List.of())))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Commit SHA");

    verify(persistenceService, never()).claimSync(any(), any());
    verify(apiClient, never()).getBlob(any(), any(), any(), any(Long.class));
  }

  @Test
  @DisplayName("固定 SHA 被删除或 Force Push 后保留旧快照并标记 SOURCE_UNAVAILABLE")
  void shouldKeepSnapshotWhenPinnedSourceDisappears() {
    when(apiClient.getBlob("demo", "repo", SAFE_BLOB, properties.getMaxFileBytes()))
        .thenThrow(new BusinessException(ErrorCode.GITHUB_REPOSITORY_NOT_FOUND));

    assertThatThrownBy(() -> service.sync(
        7L, 9L, new SyncGithubRepositoryRequest(
            COMMIT, List.of("src/main/java/demo/App.java"), List.of())))
        .isInstanceOf(BusinessException.class);

    verify(persistenceService, never()).replaceSnapshot(any(), anyList(), anyList(), any(),
        any(Integer.class), any(Long.class), any(Integer.class));
    verify(persistenceService).markSyncFailure(
        7L, 9L, GithubRepositorySyncStatus.SOURCE_UNAVAILABLE,
        "固定 SHA 在 GitHub 已不可用，已保留最后一次成功快照");
  }

  @Test
  @DisplayName("快照已保存但向量索引失败时保留 PARTIAL 并允许重试")
  void shouldKeepPartialStatusWhenEmbeddingIndexFails() {
    doThrow(new IllegalStateException("embedding provider unavailable"))
        .when(evidenceIndexer).replace(eq(repository), anyList());

    GithubSyncResultDTO result = service.sync(
        7L,
        9L,
        new SyncGithubRepositoryRequest(
            COMMIT, List.of("src/main/java/demo/App.java"), List.of()));

    assertThat(result.status()).isEqualTo(GithubRepositorySyncStatus.PARTIAL);
    verify(persistenceService).replaceSnapshot(
        eq(repository), eq(manifest), anyList(), any(), eq(1), any(Long.class), eq(0));
    verify(persistenceService).markSyncFailure(
        7L,
        9L,
        GithubRepositorySyncStatus.PARTIAL,
        "固定 SHA 快照已保存，但向量索引暂不可用；可稍后重试同步");
  }

  @Test
  @DisplayName("相同用户、SHA 和选择指纹重复同步时复用快照")
  void shouldReuseIdempotentSnapshot() {
    ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
    doAnswer(invocation -> {
      repository.setSyncStatus(GithubRepositorySyncStatus.SYNCED);
      repository.setSyncFingerprint(invocation.getArgument(3));
      repository.setSyncedFileCount(invocation.getArgument(4));
      repository.setSyncedBytes(invocation.getArgument(5));
      return null;
    }).when(persistenceService).replaceSnapshot(
        eq(repository), anyList(), anyList(), fingerprint.capture(), any(Integer.class),
        any(Long.class), any(Integer.class));
    when(persistenceService.countCompletelyIndexedEvidence(7L, 9L, COMMIT))
        .thenReturn(1);
    SyncGithubRepositoryRequest request = new SyncGithubRepositoryRequest(
        COMMIT, List.of("src/main/java/demo/App.java"), List.of());

    service.sync(7L, 9L, request);
    GithubSyncResultDTO second = service.sync(7L, 9L, request);

    assertThat(second.reusedSnapshot()).isTrue();
    verify(apiClient, org.mockito.Mockito.times(1))
        .getBlob("demo", "repo", SAFE_BLOB, properties.getMaxFileBytes());
    assertThat(fingerprint.getValue()).hasSize(64);
  }

  @Test
  @DisplayName("状态为 SYNCED 但证据未完成向量回写时必须重新同步")
  void shouldNotReuseSnapshotWithoutCompleteEmbeddingIds() {
    doAnswer(invocation -> {
      repository.setSyncStatus(GithubRepositorySyncStatus.SYNCED);
      repository.setSyncFingerprint(invocation.getArgument(3));
      repository.setSyncedFileCount(invocation.getArgument(4));
      repository.setSyncedBytes(invocation.getArgument(5));
      return null;
    }).when(persistenceService).replaceSnapshot(
        eq(repository), anyList(), anyList(), any(), any(Integer.class),
        any(Long.class), any(Integer.class));
    when(persistenceService.countCompletelyIndexedEvidence(7L, 9L, COMMIT))
        .thenReturn(0);
    SyncGithubRepositoryRequest request = new SyncGithubRepositoryRequest(
        COMMIT, List.of("src/main/java/demo/App.java"), List.of());

    service.sync(7L, 9L, request);
    GithubSyncResultDTO retried = service.sync(7L, 9L, request);

    assertThat(retried.reusedSnapshot()).isFalse();
    verify(apiClient, org.mockito.Mockito.times(2))
        .getBlob("demo", "repo", SAFE_BLOB, properties.getMaxFileBytes());
  }

  private GithubRepositoryFileEntity file(String path, String blobSha) {
    return GithubRepositoryFileEntity.builder()
        .userId(7L).repositoryId(9L).commitSha(COMMIT).path(path).blobSha(blobSha)
        .byteSize(100L).language(path.endsWith(".java") ? "Java" : "YAML")
        .fileKind(path.endsWith(".java") ? GithubFileKind.SOURCE : GithubFileKind.CONFIG)
        .status(GithubFileStatus.ELIGIBLE).defaultIncluded(true).build();
  }

  private BlobContent blob(String sha, String content) {
    return new BlobContent(sha, content.getBytes(StandardCharsets.UTF_8));
  }
}
