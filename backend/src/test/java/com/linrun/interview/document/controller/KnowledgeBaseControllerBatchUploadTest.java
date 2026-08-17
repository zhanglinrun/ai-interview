package com.linrun.interview.document.controller;

import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.document.constant.DocumentAccessScope;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.constant.KnowledgeBaseType;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.service.DocumentProcessService;
import com.linrun.interview.document.service.KnowledgeDocumentService;
import com.linrun.interview.document.service.KnowledgeDocumentVersionService;
import com.linrun.interview.document.service.impl.KnowledgeBaseAccessService;
import com.linrun.interview.document.service.impl.KnowledgeBaseListService;
import com.linrun.interview.rag.service.KnowledgeBaseQueryService;
import com.linrun.interview.rag.service.RagDatasetService;
import com.linrun.interview.rag.service.RagEvaluationService;
import com.linrun.interview.rag.service.RagQueryTraceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库批量上传")
class KnowledgeBaseControllerBatchUploadTest {

  @Mock private DocumentProcessService documentProcessService;
  @Mock private KnowledgeDocumentService knowledgeDocumentService;
  @Mock private KnowledgeDocumentVersionService versionService;
  @Mock private KnowledgeBaseQueryService queryService;
  @Mock private RagEvaluationService ragEvaluationService;
  @Mock private RagQueryTraceService ragQueryTraceService;
  @Mock private RagDatasetService ragDatasetService;
  @Mock private KnowledgeBaseListService listService;
  @Mock private KnowledgeBaseAccessService accessService;

  @InjectMocks private KnowledgeBaseController controller;

  @BeforeEach
  void setUp() {
    UserContext.setUserId(7L);
  }

  @AfterEach
  void tearDown() {
    UserContext.clear();
  }

  @Test
  @DisplayName("DOCUMENT_SEARCH 批量上传只接收并排队解析，请求内不 split")
  void batchUploadDocumentSearchEnqueuesConvert() {
    MultipartFile file = xlsxFile("notes.xlsx");
    stubAccepted(101L, "notes.xlsx", KnowledgeBaseType.DOCUMENT_SEARCH, DocumentStatus.UPLOADED);

    Result<Map<String, Object>> response =
        controller.uploadKnowledgeBaseBatch(List.of(file), "java", null, "PRIVATE");

    assertThat(response.getData().get("success")).isEqualTo(1);
    verify(documentProcessService).acceptAndEnqueueConvert(
        eq(file), isNull(), eq("java"), eq(DocumentAccessScope.PRIVATE), isNull(),
        eq(KnowledgeBaseType.DOCUMENT_SEARCH), eq(true));
    verify(documentProcessService, never()).upload(
        any(MultipartFile.class), any(), any(), any(), any(), any());
    verify(documentProcessService, never()).split(any());
  }

  @Test
  @DisplayName("DATA_QUERY 批量上传排队解析且不要求 split")
  void batchUploadDataQuerySkipsSplit() {
    MultipartFile file = xlsxFile("sales.xlsx");
    stubAccepted(102L, "sales.xlsx", KnowledgeBaseType.DATA_QUERY, DocumentStatus.UPLOADED);

    Result<Map<String, Object>> response =
        controller.uploadKnowledgeBaseBatch(List.of(file), "data", "DATA_QUERY", "PRIVATE");

    assertThat(response.getData().get("success")).isEqualTo(1);
    verify(documentProcessService).acceptAndEnqueueConvert(
        eq(file), isNull(), eq("data"), eq(DocumentAccessScope.PRIVATE), isNull(),
        eq(KnowledgeBaseType.DATA_QUERY), eq(false));
    verify(documentProcessService, never()).split(any());
  }

  @Test
  @DisplayName("批量多个文件各自接收，单个失败不影响其余")
  void batchUploadAcceptsEachFile() {
    MultipartFile first = xlsxFile("a.xlsx");
    MultipartFile second = xlsxFile("b.xlsx");
    when(documentProcessService.acceptAndEnqueueConvert(
        eq(first), isNull(), eq("java"), eq(DocumentAccessScope.PRIVATE), isNull(),
        eq(KnowledgeBaseType.DOCUMENT_SEARCH), eq(true)))
        .thenReturn(201L);
    when(documentProcessService.acceptAndEnqueueConvert(
        eq(second), isNull(), eq("java"), eq(DocumentAccessScope.PRIVATE), isNull(),
        eq(KnowledgeBaseType.DOCUMENT_SEARCH), eq(true)))
        .thenThrow(new RuntimeException("minio down"));
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.setId(201L);
    entity.setName("a.xlsx");
    entity.setDocStatus(DocumentStatus.UPLOADED);
    entity.setStorageKey("kb/a.xlsx");
    entity.setStorageUrl("http://minio/kb/a.xlsx");
    when(accessService.requireOwner(201L)).thenReturn(entity);

    Result<Map<String, Object>> response =
        controller.uploadKnowledgeBaseBatch(List.of(first, second), "java", null, "PRIVATE");

    assertThat(response.getData().get("success")).isEqualTo(1);
    assertThat(response.getData().get("failed")).isEqualTo(1);
    verify(documentProcessService).acceptAndEnqueueConvert(
        eq(first), isNull(), eq("java"), eq(DocumentAccessScope.PRIVATE), isNull(),
        eq(KnowledgeBaseType.DOCUMENT_SEARCH), eq(true));
    verify(documentProcessService).acceptAndEnqueueConvert(
        eq(second), isNull(), eq("java"), eq(DocumentAccessScope.PRIVATE), isNull(),
        eq(KnowledgeBaseType.DOCUMENT_SEARCH), eq(true));
  }

  @Test
  @DisplayName("超过单次文件数上限时直接拒绝，避免 Tomcat 静默截断")
  void batchUploadRejectsTooManyFiles() {
    List<MultipartFile> files = IntStream.range(0, KnowledgeBaseController.MAX_BATCH_FILES + 1)
        .mapToObj(index -> xlsxFile("file-" + index + ".xlsx"))
        .toList();

    assertThatThrownBy(() ->
        controller.uploadKnowledgeBaseBatch(files, "java", null, "PRIVATE"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining(String.valueOf(KnowledgeBaseController.MAX_BATCH_FILES));
    verify(documentProcessService, never()).acceptAndEnqueueConvert(
        any(MultipartFile.class), any(), any(), any(), any(), any(), any(Boolean.class));
  }

  private MultipartFile xlsxFile(String name) {
    return new MockMultipartFile(
        "files",
        name,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[] {1, 2, 3});
  }

  private void stubAccepted(Long docId, String name, KnowledgeBaseType kbType, DocumentStatus status) {
    when(documentProcessService.acceptAndEnqueueConvert(
        any(MultipartFile.class), isNull(), any(), any(), isNull(), eq(kbType), any(Boolean.class)))
        .thenReturn(docId);
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.setId(docId);
    entity.setName(name);
    entity.setCategory("cat");
    entity.setDocStatus(status);
    entity.setKnowledgeBaseType(kbType);
    entity.setStorageKey("kb/" + name);
    entity.setStorageUrl("http://minio/kb/" + name);
    when(accessService.requireOwner(docId)).thenReturn(entity);
  }
}
