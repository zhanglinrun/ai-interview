package com.linrun.interview.document.service;

import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.document.constant.DocumentAccessScope;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.constant.FileType;
import com.linrun.interview.document.constant.KnowledgeBaseType;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.document.mapper.TableMetaMapper;
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
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("知识库上传主链路")
class DocumentUploadFlowTest {

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
  @DisplayName("DOCUMENT_SEARCH + Markdown：upload → convert → CONVERTED")
  void markdownUploadFlow() {
    MultipartFile file = mockFile("note.md", "text/plain", "# 面试笔记\n");
    stubCommonUpload(file, FileType.MARKDOWN, KnowledgeBaseType.DOCUMENT_SEARCH);
    when(fileProcessor.processDocument(any(DocumentParseRequest.class)))
        .thenReturn("# 面试笔记\n");
    when(storageService.uploadConvertedMarkdown(eq(100L), eq(200L), eq("# 面试笔记\n")))
        .thenReturn("https://minio/converted/100/200/full.md");

    Long docId = service.upload(file, "note", "java", DocumentAccessScope.PRIVATE, null);

    assertThat(docId).isEqualTo(100L);
    verify(knowledgeDocumentService).advanceDocumentAndVersionStatus(
        100L, 200L, DocumentStatus.CONVERTING);
    verify(knowledgeDocumentService).advanceDocumentAndVersionStatus(
        100L, 200L, DocumentStatus.CONVERTED);
    verify(storageService).uploadConvertedMarkdown(100L, 200L, "# 面试笔记\n");
  }

  @Test
  @DisplayName("DOCUMENT_SEARCH + MinerU 返回 URL：直接写 convertedDocUrl")
  void mineruUrlUploadFlow() {
    MultipartFile file = mockFile("paper.pdf", "application/pdf", "%PDF");
    stubCommonUpload(file, FileType.PDF, KnowledgeBaseType.DOCUMENT_SEARCH);
    when(fileProcessor.processDocument(any(DocumentParseRequest.class)))
        .thenReturn("https://minio/converted/100/200/full.md");

    service.upload(file, "paper", null, DocumentAccessScope.PRIVATE, null,
        KnowledgeBaseType.DOCUMENT_SEARCH);

    ArgumentCaptor<KnowledgeBaseVersionEntity> versionCaptor =
        ArgumentCaptor.forClass(KnowledgeBaseVersionEntity.class);
    verify(versionService).updateVersion(versionCaptor.capture());
    assertThat(versionCaptor.getValue().getConvertedDocUrl())
        .isEqualTo("https://minio/converted/100/200/full.md");
  }

  @Test
  @DisplayName("DATA_QUERY + Excel：upload → import → STORED，不写 convertedDocUrl")
  void dataQueryExcelUploadFlow() {
    MultipartFile file = mockFile("sales.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
    stubCommonUpload(file, FileType.EXCEL, KnowledgeBaseType.DATA_QUERY);
    when(excelProcessService.generatePhysicalTableName(7L, "sales.xlsx"))
        .thenReturn("custom_data_query_u7_sales");
    when(fileProcessor.processDocument(any(DocumentParseRequest.class))).thenReturn(null);

    Long docId = service.upload(file, "sales", "data", DocumentAccessScope.PRIVATE, null,
        KnowledgeBaseType.DATA_QUERY);

    assertThat(docId).isEqualTo(100L);
    verify(knowledgeDocumentService).advanceDocumentAndVersionStatus(
        100L, 200L, DocumentStatus.STORED);
    verify(knowledgeDocumentService, org.mockito.Mockito.never())
        .advanceDocumentAndVersionStatus(100L, 200L, DocumentStatus.CONVERTED);
    verify(storageService, org.mockito.Mockito.never())
        .uploadConvertedMarkdown(anyLong(), anyLong(), any());
  }

  @Test
  @DisplayName("DATA_QUERY 拒绝非表格文件")
  void dataQueryRejectsNonSpreadsheet() {
    MultipartFile file = mockFile("note.md", "text/plain", "# hi");
    when(fileTypeResolver.resolve(eq("note.md"), any())).thenReturn(FileType.MARKDOWN);

    assertThatThrownBy(() -> service.upload(
        file, "note", null, DocumentAccessScope.PRIVATE, null, KnowledgeBaseType.DATA_QUERY))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("DATA_QUERY 仅支持 Excel/CSV");
  }

  @Test
  @DisplayName("uploadNewVersion 沿用知识库类型路由解析器")
  void newVersionUsesExistingKbType() {
    MultipartFile file = mockFile("sales-v2.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx2");
    KnowledgeBaseEntity existing = new KnowledgeBaseEntity();
    existing.setId(100L);
    existing.setUserId(7L);
    existing.setCurrentVersionId(200L);
    existing.setKnowledgeBaseType(KnowledgeBaseType.DATA_QUERY);
    when(knowledgeBaseEntityMapper.selectOne(any())).thenReturn(existing);

    KnowledgeBaseVersionEntity latest = new KnowledgeBaseVersionEntity();
    latest.setVersionId(200L);
    latest.setVersion("1.0.0");
    latest.setStatus(DocumentStatus.STORED);
    when(versionService.findLatestByDocId(100L)).thenReturn(Optional.of(latest));

    when(fileHashService.calculateHash(file)).thenReturn("hash-v2");
    when(versionService.findByContentHash("hash-v2", 7L)).thenReturn(Optional.empty());
    stubFileValidation();
    when(storageService.uploadKnowledgeBase(file)).thenReturn("kb/sales-v2");
    when(storageService.getFileUrl("kb/sales-v2")).thenReturn("http://minio/kb/sales-v2");
    when(contentTypeDetectionService.detectContentType(file))
        .thenReturn("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    when(fileTypeResolver.resolve(eq("sales-v2.xlsx"), any())).thenReturn(FileType.EXCEL);
    when(fileProcessServiceFactory.get(FileType.EXCEL, KnowledgeBaseType.DATA_QUERY))
        .thenReturn(fileProcessor);
    when(fileProcessor.processDocument(any(DocumentParseRequest.class))).thenReturn(null);
    doAnswer(inv -> {
      KnowledgeBaseVersionEntity v = inv.getArgument(0);
      v.setVersionId(201L);
      return v;
    }).when(versionService).saveVersion(any());

    Long versionId = service.uploadNewVersion(100L, file, "v2");

    assertThat(versionId).isEqualTo(201L);
    verify(fileProcessServiceFactory).get(FileType.EXCEL, KnowledgeBaseType.DATA_QUERY);
    verify(knowledgeDocumentService).advanceDocumentAndVersionStatus(
        100L, 201L, DocumentStatus.STORED);
  }

  private MultipartFile mockFile(String name, String contentType, String body) {
    return new MockMultipartFile("file", name, contentType, body.getBytes(StandardCharsets.UTF_8));
  }

  private void stubCommonUpload(MultipartFile file, FileType fileType, KnowledgeBaseType kbType) {
    stubFileValidation();
    when(fileHashService.calculateHash(file)).thenReturn("hash1");
    when(versionService.findByContentHash("hash1", 7L)).thenReturn(Optional.empty());
    when(storageService.uploadKnowledgeBase(file)).thenReturn("kb/key");
    when(storageService.getFileUrl("kb/key")).thenReturn("http://minio/kb/key");
    when(contentTypeDetectionService.detectContentType(file)).thenReturn(file.getContentType());
    when(fileTypeResolver.resolve(eq(file.getOriginalFilename()), any())).thenReturn(fileType);
    when(fileProcessServiceFactory.get(fileType, kbType)).thenReturn(fileProcessor);
    doAnswer(inv -> {
      KnowledgeBaseEntity e = inv.getArgument(0);
      if (e.getId() == null) {
        e.setId(100L);
      }
      return 1;
    }).when(knowledgeBaseEntityMapper).insert(any(KnowledgeBaseEntity.class));
    doAnswer(inv -> {
      KnowledgeBaseVersionEntity v = inv.getArgument(0);
      if (v.getVersionId() == null) {
        v.setVersionId(200L);
      }
      return v;
    }).when(versionService).saveVersion(any());
    when(versionService.findById(200L)).thenAnswer(inv -> {
      KnowledgeBaseVersionEntity v = new KnowledgeBaseVersionEntity();
      v.setVersionId(200L);
      v.setDocId(100L);
      return Optional.of(v);
    });
    if (kbType == KnowledgeBaseType.DATA_QUERY) {
      when(excelProcessService.generatePhysicalTableName(7L, file.getOriginalFilename()))
          .thenReturn("custom_data_query_u7_table");
    }
  }

  private void stubFileValidation() {
    when(fileValidationService.isKnowledgeBaseMimeType(any())).thenReturn(true);
    when(fileValidationService.isMarkdownExtension(any())).thenReturn(true);
    when(fileValidationService.isSpreadsheetExtension(any())).thenReturn(true);
    when(knowledgeBaseEntityMapper.updateById(any(KnowledgeBaseEntity.class))).thenReturn(1);
  }
}
