package com.linrun.interview.document.service;import com.linrun.interview.rag.service.EvidenceSnapshotService;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.document.service.FileStorageService;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.constant.SegmentStatus;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.chat.mapper.RagSessionKnowledgeBaseMapper;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.entity.KnowledgeBaseSegmentEntity;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库文档服务测试")
class KnowledgeDocumentServiceImplTest {

  private static final String EMBEDDING_CLAIM = "20:1:2026-07-19T12:00";

  @Mock private KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;
  @Mock private KnowledgeDocumentVersionService versionService;
  @Mock private KnowledgeSegmentService segmentService;
  @Mock private VectorStoreService vectorStoreService;
  @Mock private FileStorageService fileStorageService;
  @Mock private SegmentTextCacheService segmentTextCacheService;
  @Mock private EvidenceSnapshotService evidenceSnapshotService;
  @Mock private DocumentParseTaskService documentParseTaskService;
  @Mock private RagSessionKnowledgeBaseMapper sessionKnowledgeBaseMapper;
  @Mock private VectorizationTaskService vectorizationTaskService;
  @Mock private TransactionTemplate transactionTemplate;

  @InjectMocks
  private KnowledgeDocumentServiceImpl service;

  @Test
  @DisplayName("删除在 ES 写入后提交时应撤销本批孤儿向量")
  void deletionAfterEsWriteRemovesOrphanBatch() throws Exception {
    KnowledgeBaseVersionEntity version = version(20L, 10L);
    KnowledgeBaseEntity document = document(10L);
    KnowledgeBaseSegmentEntity segment = segment(1L, 10L, 20L);
    CountDownLatch esWritten = new CountDownLatch(1);
    CountDownLatch deletionCommitted = new CountDownLatch(1);
    AtomicBoolean deleted = new AtomicBoolean();

    when(versionService.findById(20L)).thenReturn(Optional.of(version));
    when(vectorizationTaskService.claim(20L)).thenReturn(
        new VectorizationTaskService.Claim(
            VectorizationTaskService.ClaimState.ACQUIRED, version));
    when(vectorizationTaskService.renew(version)).thenReturn(true);
    when(vectorizationTaskService.fail(eq(version), any(BusinessException.class))).thenReturn(true);
    when(knowledgeBaseEntityMapper.selectById(10L)).thenReturn(document);
    when(segmentService.listPendingEmbedding(
        10L, 20L, SegmentStatus.STORED, 100)).thenReturn(List.of(segment));
    when(vectorStoreService.embedAndStore(anyList(), eq(EMBEDDING_CLAIM))).thenAnswer(invocation -> {
      esWritten.countDown();
      if (!deletionCommitted.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("删除事务未按预期提交");
      }
      return List.of("kb-segment-1");
    });
    when(segmentService.batchUpdateEmbedding(
        10L, 20L, 1, version.getEmbeddingClaimedAt(), List.of(segment)))
        .thenAnswer(invocation -> deleted.get() ? 0 : 1);

    CompletableFuture<Void> vectorizing = CompletableFuture.runAsync(
        () -> service.activateVersion(version));
    assertThat(esWritten.await(2, TimeUnit.SECONDS)).isTrue();
    deleted.set(true);
    deletionCommitted.countDown();

    assertThatThrownBy(() -> vectorizing.get(2, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(BusinessException.class);
    verify(vectorStoreService).removeByEmbeddingClaim(EMBEDDING_CLAIM);
    verify(vectorizationTaskService).fail(eq(version), any(BusinessException.class));
    verify(vectorizationTaskService, never()).complete(version);
    verify(versionService, never()).update(any());
    verify(knowledgeBaseEntityMapper, never()).updateById(any(KnowledgeBaseEntity.class));
  }

  @Test
  @DisplayName("DB 回写异常时应撤销已经写入的 ES 向量")
  void dbWriteFailureRemovesStoredBatch() {
    KnowledgeBaseVersionEntity version = version(20L, 10L);
    KnowledgeBaseSegmentEntity segment = segment(1L, 10L, 20L);
    when(versionService.findById(20L)).thenReturn(Optional.of(version));
    when(vectorizationTaskService.claim(20L)).thenReturn(
        new VectorizationTaskService.Claim(
            VectorizationTaskService.ClaimState.ACQUIRED, version));
    when(vectorizationTaskService.renew(version)).thenReturn(true);
    when(vectorizationTaskService.fail(eq(version), any(BusinessException.class))).thenReturn(true);
    when(knowledgeBaseEntityMapper.selectById(10L)).thenReturn(document(10L));
    when(segmentService.listPendingEmbedding(
        10L, 20L, SegmentStatus.STORED, 100)).thenReturn(List.of(segment));
    when(vectorStoreService.embedAndStore(List.of(segment), EMBEDDING_CLAIM))
        .thenReturn(List.of("kb-segment-1"));
    when(segmentService.batchUpdateEmbedding(
        10L, 20L, 1, version.getEmbeddingClaimedAt(), List.of(segment)))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThatThrownBy(() -> service.activateVersion(version))
        .isInstanceOf(BusinessException.class);

    verify(vectorStoreService).removeByEmbeddingClaim(EMBEDDING_CLAIM);
    verify(vectorizationTaskService).fail(eq(version), any(BusinessException.class));
    verify(vectorizationTaskService, never()).complete(version);
  }

  @Test
  @DisplayName("DB 回写失去租约后只按旧批次令牌清理 ES")
  void lostLeaseCleansOnlyOldEmbeddingClaim() {
    KnowledgeBaseVersionEntity version = version(20L, 10L);
    KnowledgeBaseSegmentEntity segment = segment(1L, 10L, 20L);
    when(versionService.findById(20L)).thenReturn(Optional.of(version));
    when(vectorizationTaskService.claim(20L)).thenReturn(
        new VectorizationTaskService.Claim(
            VectorizationTaskService.ClaimState.ACQUIRED, version));
    when(vectorizationTaskService.renew(version)).thenReturn(true);
    when(vectorizationTaskService.fail(eq(version), any(BusinessException.class))).thenReturn(false);
    when(knowledgeBaseEntityMapper.selectById(10L)).thenReturn(document(10L));
    when(segmentService.listPendingEmbedding(
        10L, 20L, SegmentStatus.STORED, 100)).thenReturn(List.of(segment));
    when(vectorStoreService.embedAndStore(List.of(segment), EMBEDDING_CLAIM))
        .thenReturn(List.of("kb-segment-1"));
    when(segmentService.batchUpdateEmbedding(
        10L, 20L, 1, version.getEmbeddingClaimedAt(), List.of(segment)))
        .thenReturn(0);

    assertThatThrownBy(() -> service.activateVersion(version))
        .isInstanceOf(BusinessException.class);

    verify(vectorStoreService).removeByEmbeddingClaim(EMBEDDING_CLAIM);
    verify(vectorizationTaskService).fail(eq(version), any(BusinessException.class));
  }

  @Test
  @DisplayName("租约已被新任务接管时旧任务不得推进文档终态")
  void staleLeaseCannotFinalizeDocument() {
    KnowledgeBaseVersionEntity version = version(20L, 10L);
    KnowledgeBaseEntity document = document(10L);
    when(versionService.findById(20L)).thenReturn(Optional.of(version));
    when(vectorizationTaskService.claim(20L)).thenReturn(
        new VectorizationTaskService.Claim(
            VectorizationTaskService.ClaimState.ACQUIRED, version));
    when(knowledgeBaseEntityMapper.selectById(10L)).thenReturn(document);
    when(segmentService.listPendingEmbedding(
        10L, 20L, SegmentStatus.STORED, 100)).thenReturn(List.of());
    when(vectorizationTaskService.complete(version)).thenReturn(false);
    when(vectorizationTaskService.fail(eq(version), any(BusinessException.class))).thenReturn(false);
    executeTransactionsImmediately();

    assertThatThrownBy(() -> service.activateVersion(version))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("租约已失效");

    verify(vectorizationTaskService).complete(version);
    verify(vectorizationTaskService).fail(eq(version), any(BusinessException.class));
    verify(knowledgeBaseEntityMapper, never()).updateById(any(KnowledgeBaseEntity.class));
  }

  @Test
  @DisplayName("文档并发变化时不得静默完成向量化终态")
  void concurrentDocumentChangeCannotFinalizeVersion() {
    KnowledgeBaseVersionEntity version = version(20L, 10L);
    KnowledgeBaseEntity document = document(10L);
    when(versionService.findById(20L)).thenReturn(Optional.of(version));
    when(vectorizationTaskService.claim(20L)).thenReturn(
        new VectorizationTaskService.Claim(
            VectorizationTaskService.ClaimState.ACQUIRED, version));
    when(knowledgeBaseEntityMapper.selectById(10L)).thenReturn(document);
    when(segmentService.listPendingEmbedding(
        10L, 20L, SegmentStatus.STORED, 100)).thenReturn(List.of());
    when(vectorizationTaskService.complete(version)).thenReturn(true);
    when(knowledgeBaseEntityMapper.updateById(document)).thenReturn(0);
    when(vectorizationTaskService.fail(eq(version), any(BusinessException.class))).thenReturn(true);
    executeTransactionsImmediately();

    assertThatThrownBy(() -> service.activateVersion(version))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("文档状态已变化");

    verify(vectorizationTaskService).complete(version);
    verify(vectorizationTaskService).fail(eq(version), any(BusinessException.class));
  }

  private KnowledgeBaseVersionEntity version(Long versionId, Long docId) {
    KnowledgeBaseVersionEntity version = new KnowledgeBaseVersionEntity();
    version.setVersionId(versionId);
    version.setDocId(docId);
    version.setStatus(DocumentStatus.CHUNKED);
    version.setEmbeddingAttempt(1);
    version.setEmbeddingClaimedAt(java.time.LocalDateTime.of(2026, 7, 19, 12, 0));
    return version;
  }

  private void executeTransactionsImmediately() {
    doAnswer(invocation -> {
      Consumer<TransactionStatus> action = invocation.getArgument(0);
      action.accept(mock(TransactionStatus.class));
      return null;
    }).when(transactionTemplate).executeWithoutResult(any());
  }

  private KnowledgeBaseEntity document(Long docId) {
    KnowledgeBaseEntity document = new KnowledgeBaseEntity();
    document.setId(docId);
    document.setDocStatus(DocumentStatus.CHUNKED);
    return document;
  }

  private KnowledgeBaseSegmentEntity segment(Long id, Long docId, Long versionId) {
    KnowledgeBaseSegmentEntity segment = new KnowledgeBaseSegmentEntity();
    segment.setId(id);
    segment.setDocumentId(docId);
    segment.setDocumentVersion(versionId);
    segment.setStatus(SegmentStatus.STORED);
    return segment;
  }
}
