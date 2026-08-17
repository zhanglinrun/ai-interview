package com.linrun.interview.document.service;

import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.document.constant.DocumentAccessScope;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.constant.FileType;
import com.linrun.interview.document.constant.KnowledgeBaseType;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import com.linrun.interview.document.event.DocumentAcceptedEvent;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.document.service.impl.ContentTypeDetectionService;
import com.linrun.interview.document.service.impl.DocumentProcessServiceImpl;
import com.linrun.interview.document.service.impl.FileHashService;
import com.linrun.interview.document.service.impl.FileTypeResolver;
import com.linrun.interview.document.service.impl.FileValidationService;
import com.linrun.interview.document.service.impl.KnowledgeBaseChunkingService;
import com.linrun.interview.document.service.impl.VectorizationTaskService;
import com.linrun.interview.document.vo.DocumentParseRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("知识库接收后异步解析")
class DocumentProcessServiceAcceptTest {

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
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private FileProcessService fileProcessor;

  @InjectMocks private DocumentProcessServiceImpl service;

  @BeforeEach
  void setUp() {
    UserContext.setUserId(7L);
  }

  @AfterEach
  void tearDown() {
    UserContext.clear();
  }

  @Test
  @DisplayName("acceptAndEnqueueConvert 只落库并发事件，不在请求内解析")
  void acceptPublishesEventWithoutConvert() {
    MultipartFile file = mockFile("paper.pdf", "application/pdf", "%PDF");
    stubPersist(file, FileType.PDF, KnowledgeBaseType.DOCUMENT_SEARCH);

    Long docId = service.acceptAndEnqueueConvert(
        file, "paper", "java", DocumentAccessScope.PRIVATE, null,
        KnowledgeBaseType.DOCUMENT_SEARCH, true);

    assertThat(docId).isEqualTo(100L);
    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue()).isInstanceOf(DocumentAcceptedEvent.class);
    DocumentAcceptedEvent event = (DocumentAcceptedEvent) eventCaptor.getValue();
    assertThat(event.docId()).isEqualTo(100L);
    assertThat(event.userId()).isEqualTo(7L);
    assertThat(event.splitAfter()).isTrue();
    verify(fileProcessServiceFactory, never()).get(any(), any());
    verify(knowledgeDocumentService, never())
        .advanceDocumentAndVersionStatus(eq(100L), eq(200L), eq(DocumentStatus.CONVERTING));
  }

  @Test
  @DisplayName("processAcceptedDocument 从对象存储拉原件再解析")
  void processAcceptedDownloadsAndConverts() {
    KnowledgeBaseEntity entity = uploadedEntity();
    when(knowledgeBaseEntityMapper.selectById(100L)).thenReturn(entity);
    KnowledgeBaseVersionEntity version = uploadedVersion();
    when(versionService.findById(200L)).thenReturn(Optional.of(version));
    when(storageService.downloadFile("kb/key")).thenReturn("%PDF-body".getBytes(StandardCharsets.UTF_8));
    when(fileTypeResolver.resolve(eq("paper.pdf"), any())).thenReturn(FileType.PDF);
    when(fileProcessServiceFactory.get(FileType.PDF, KnowledgeBaseType.DOCUMENT_SEARCH))
        .thenReturn(fileProcessor);
    when(fileProcessor.processDocument(any(DocumentParseRequest.class)))
        .thenReturn("https://minio/converted/100/200/full.md");
    when(knowledgeBaseEntityMapper.updateById(any(KnowledgeBaseEntity.class))).thenReturn(1);

    service.processAcceptedDocument(100L, false);

    verify(storageService).downloadFile("kb/key");
    verify(knowledgeDocumentService).advanceDocumentAndVersionStatus(
        100L, 200L, DocumentStatus.CONVERTING);
    verify(knowledgeDocumentService).advanceDocumentAndVersionStatus(
        100L, 200L, DocumentStatus.CONVERTED);
    verify(fileProcessor).processDocument(any(DocumentParseRequest.class));
  }

  private void stubPersist(MultipartFile file, FileType fileType, KnowledgeBaseType kbType) {
    when(fileValidationService.isKnowledgeBaseMimeType(any())).thenReturn(true);
    when(fileValidationService.isMarkdownExtension(any())).thenReturn(true);
    when(fileValidationService.isSpreadsheetExtension(any())).thenReturn(true);
    when(fileHashService.calculateHash(file)).thenReturn("hash1");
    when(versionService.findByContentHash("hash1", 7L)).thenReturn(Optional.empty());
    when(storageService.uploadKnowledgeBase(file)).thenReturn("kb/key");
    when(storageService.getFileUrl("kb/key")).thenReturn("http://minio/kb/key");
    when(contentTypeDetectionService.detectContentType(file)).thenReturn(file.getContentType());
    when(fileTypeResolver.resolve(eq(file.getOriginalFilename()), any())).thenReturn(fileType);
    doAnswer(inv -> {
      KnowledgeBaseEntity e = inv.getArgument(0);
      if (e.getId() == null) {
        e.setId(100L);
      }
      return 1;
    }).when(knowledgeBaseEntityMapper).insert(any(KnowledgeBaseEntity.class));
    when(knowledgeBaseEntityMapper.updateById(any(KnowledgeBaseEntity.class))).thenReturn(1);
    doAnswer(inv -> {
      KnowledgeBaseVersionEntity v = inv.getArgument(0);
      if (v.getVersionId() == null) {
        v.setVersionId(200L);
      }
      return v;
    }).when(versionService).saveVersion(any());
  }

  private KnowledgeBaseEntity uploadedEntity() {
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.setId(100L);
    entity.setUserId(7L);
    entity.setName("paper");
    entity.setOriginalFilename("paper.pdf");
    entity.setContentType("application/pdf");
    entity.setStorageKey("kb/key");
    entity.setCurrentVersionId(200L);
    entity.setDocStatus(DocumentStatus.UPLOADED);
    entity.setKnowledgeBaseType(KnowledgeBaseType.DOCUMENT_SEARCH);
    return entity;
  }

  private KnowledgeBaseVersionEntity uploadedVersion() {
    KnowledgeBaseVersionEntity version = new KnowledgeBaseVersionEntity();
    version.setVersionId(200L);
    version.setDocId(100L);
    version.setStatus(DocumentStatus.UPLOADED);
    version.setStorageKey("kb/key");
    return version;
  }

  private MultipartFile mockFile(String name, String contentType, String body) {
    return new MockMultipartFile("file", name, contentType, body.getBytes(StandardCharsets.UTF_8));
  }
}
