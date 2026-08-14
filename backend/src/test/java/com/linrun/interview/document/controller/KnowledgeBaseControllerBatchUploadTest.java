package com.linrun.interview.document.controller;

import com.linrun.interview.auth.security.UserContext;
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

import static org.assertj.core.api.Assertions.assertThat;
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
  @DisplayName("DOCUMENT_SEARCH 批量上传成功后自动 split")
  void batchUploadDocumentSearchTriggersSplit() {
    MultipartFile file = xlsxFile("notes.xlsx");
    stubUpload(101L, "notes.xlsx", KnowledgeBaseType.DOCUMENT_SEARCH, DocumentStatus.CONVERTED);
    when(documentProcessService.split(101L)).thenReturn(5);

    Result<Map<String, Object>> response =
        controller.uploadKnowledgeBaseBatch(List.of(file), "java", null, "PRIVATE");

    assertThat(response.getData().get("success")).isEqualTo(1);
    verify(documentProcessService).upload(
        eq(file), isNull(), eq("java"), eq(DocumentAccessScope.PRIVATE), isNull(),
        eq(KnowledgeBaseType.DOCUMENT_SEARCH));
    verify(documentProcessService).split(101L);
  }

  @Test
  @DisplayName("DATA_QUERY 批量上传跳过 split")
  void batchUploadDataQuerySkipsSplit() {
    MultipartFile file = xlsxFile("sales.xlsx");
    stubUpload(102L, "sales.xlsx", KnowledgeBaseType.DATA_QUERY, DocumentStatus.STORED);

    Result<Map<String, Object>> response =
        controller.uploadKnowledgeBaseBatch(List.of(file), "data", "DATA_QUERY", "PRIVATE");

    assertThat(response.getData().get("success")).isEqualTo(1);
    verify(documentProcessService).upload(
        eq(file), isNull(), eq("data"), eq(DocumentAccessScope.PRIVATE), isNull(),
        eq(KnowledgeBaseType.DATA_QUERY));
    verify(documentProcessService, never()).split(any());
  }

  private MultipartFile xlsxFile(String name) {
    return new MockMultipartFile(
        "files",
        name,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[] {1, 2, 3});
  }

  private void stubUpload(Long docId, String name, KnowledgeBaseType kbType, DocumentStatus status) {
    when(documentProcessService.upload(
        any(MultipartFile.class), isNull(), any(), any(), isNull(), eq(kbType)))
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
