package com.linrun.interview.document.service;

import com.linrun.interview.document.service.impl.DocumentProcessServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.document.service.impl.ContentTypeDetectionService;
import com.linrun.interview.document.service.impl.FileHashService;
import com.linrun.interview.document.service.FileStorageService;
import com.linrun.interview.document.service.impl.FileValidationService;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.document.mapper.TableMetaMapper;
import com.linrun.interview.document.service.ExcelProcessService;
import com.linrun.interview.document.service.FileProcessServiceFactory;
import com.linrun.interview.document.service.impl.FileTypeResolver;
import com.linrun.interview.document.service.impl.KnowledgeBaseChunkingService;
import com.linrun.interview.document.service.impl.VectorizationTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库文档处理服务测试")
class DocumentProcessServiceImplTest {

    @Mock private FileProcessServiceFactory fileProcessServiceFactory;
    @Mock private FileTypeResolver fileTypeResolver;
    @Mock private FileStorageService storageService;
    @Mock private FileHashService fileHashService;
    @Mock private FileValidationService fileValidationService;
    @Mock private ContentTypeDetectionService contentTypeDetectionService;
    @Mock private KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;
    @Mock private KnowledgeDocumentVersionService versionService;
    @Mock private KnowledgeSegmentService segmentService;
    @Mock private KnowledgeBaseChunkingService chunkingService;
    @Mock private KnowledgeDocumentService knowledgeDocumentService;
    @Mock private VectorStoreService vectorStoreService;
    @Mock private VectorizationTaskService vectorizationTaskService;
    @Mock private ExcelProcessService excelProcessService;
    @Mock private TableMetaMapper tableMetaMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ObjectMapper objectMapper;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks
    private DocumentProcessServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(7L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("文档已切块时应直接返回现有分段数")
    void splitUsesVersionStatus() {
        KnowledgeBaseEntity doc = doc(1L, 3L);
        doc.setDocStatus(DocumentStatus.CHUNKED);
        when(knowledgeBaseEntityMapper.selectOne(any())).thenReturn(doc);
        when(segmentService.countByDocumentVersion(3L)).thenReturn(2L);

        assertThat(service.split(1L)).isEqualTo(2);
    }

    @Test
    @DisplayName("重新切块状态竞争时不得继续删除分段")
    void rechunkStopsWhenAtomicStateTransitionFails() {
        KnowledgeBaseEntity doc = doc(1L, 3L);
        doc.setDocStatus(DocumentStatus.VECTOR_STORED);
        KnowledgeBaseVersionEntity version = version(
            1L, 3L, DocumentStatus.VECTOR_STORED, "rag.md");
        when(knowledgeBaseEntityMapper.selectOne(any())).thenReturn(doc);
        when(versionService.findById(3L)).thenReturn(Optional.of(version));
        when(knowledgeBaseEntityMapper.beginRechunk(1L, 3L)).thenReturn(0);
        executeTransactionsImmediately();

        assertThatThrownBy(() -> service.rechunk(1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("文档状态已变化");

        verify(vectorStoreService, never()).removeByDocIdAndVersion(1L, 3L);
        verify(segmentService, never()).physicalDeleteByDocumentVersion(3L);
        verify(vectorizationTaskService, never()).reset(3L);
    }

    @Test
    @DisplayName("重新切块必须先接管数据库状态再删除 ES")
    void rechunkFencesDatabaseBeforeDeletingVectors() {
        KnowledgeBaseEntity doc = doc(1L, 3L);
        doc.setDocStatus(DocumentStatus.VECTOR_STORED);
        KnowledgeBaseVersionEntity version = version(
            1L, 3L, DocumentStatus.VECTOR_STORED, "rag.md");
        when(knowledgeBaseEntityMapper.selectOne(any())).thenReturn(doc);
        when(versionService.findById(3L)).thenReturn(Optional.of(version));
        when(knowledgeBaseEntityMapper.beginRechunk(1L, 3L)).thenReturn(1);
        when(versionService.beginRechunk(3L, 1L)).thenReturn(true);
        doThrow(new IllegalStateException("ES unavailable"))
            .when(vectorStoreService).removeByDocIdAndVersion(1L, 3L);
        executeTransactionsImmediately();

        assertThatThrownBy(() -> service.rechunk(1L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ES unavailable");

        InOrder order = inOrder(knowledgeBaseEntityMapper, versionService, vectorStoreService);
        order.verify(knowledgeBaseEntityMapper).beginRechunk(1L, 3L);
        order.verify(versionService).beginRechunk(3L, 1L);
        order.verify(vectorStoreService).removeByDocIdAndVersion(1L, 3L);
        verify(segmentService, never()).physicalDeleteByDocumentVersion(3L);
        verify(vectorizationTaskService, never()).reset(3L);
    }

    @Test
    @DisplayName("重切块中断后直接 split 也应先恢复旧向量清理")
    void splitResumesInterruptedRechunkCleanup() {
        KnowledgeBaseEntity doc = doc(1L, 3L);
        KnowledgeBaseVersionEntity version = version(
            1L, 3L, DocumentStatus.CONVERTED, "rag.md");
        when(knowledgeBaseEntityMapper.selectOne(any())).thenReturn(doc);
        when(versionService.findById(3L)).thenReturn(Optional.of(version));
        when(segmentService.countByDocumentVersion(3L)).thenReturn(2L);
        doThrow(new IllegalStateException("ES unavailable"))
            .when(vectorStoreService).removeByDocIdAndVersion(1L, 3L);

        assertThatThrownBy(() -> service.split(1L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ES unavailable");

        verify(vectorStoreService).removeByDocIdAndVersion(1L, 3L);
        verify(segmentService, never()).physicalDeleteByDocumentVersion(3L);
    }

    private void executeTransactionsImmediately() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private KnowledgeBaseEntity doc(Long docId, Long currentVersionId) {
        KnowledgeBaseEntity doc = new KnowledgeBaseEntity();
        doc.setId(docId);
        doc.setUserId(7L);
        doc.setCurrentVersionId(currentVersionId);
        doc.setDocStatus(DocumentStatus.CONVERTED);
        return doc;
    }

    private KnowledgeBaseVersionEntity version(Long docId, Long versionId,
                                               DocumentStatus status, String fileName) {
        KnowledgeBaseVersionEntity version = new KnowledgeBaseVersionEntity();
        version.setDocId(docId);
        version.setVersionId(versionId);
        version.setStatus(status);
        version.setDocUrl("http://localhost/bucket/" + fileName);
        version.setConvertedDocUrl("http://localhost/bucket/converted/1/3/full.md");
        return version;
    }
}
