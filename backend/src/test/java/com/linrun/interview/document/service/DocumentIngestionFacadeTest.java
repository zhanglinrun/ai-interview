package com.linrun.interview.document.service;

import com.linrun.interview.document.constant.DocumentAccessScope;
import com.linrun.interview.document.constant.KnowledgeBaseType;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import com.linrun.interview.document.service.impl.DocumentIngestionFacade;
import com.linrun.interview.document.service.impl.DocumentParseTaskService;
import com.linrun.interview.document.service.impl.KnowledgeBaseListService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("文档入库 Facade 上传流程")
class DocumentIngestionFacadeTest {

  @Mock private DocumentProcessService processService;
  @Mock private KnowledgeDocumentService documentService;
  @Mock private KnowledgeDocumentVersionService versionService;
  @Mock private DocumentParseTaskService parseTaskService;
  @Mock private KnowledgeBaseListService listService;

  @InjectMocks private DocumentIngestionFacade facade;

  @Test
  @DisplayName("DOCUMENT_SEARCH 上传后自动 split")
  void uploadTriggersSplitForDocumentSearch() {
    MultipartFile file = new MockMultipartFile("file", "note.md", "text/plain", "# hi".getBytes());
    when(processService.upload(any(), any(), any(), any(), any())).thenReturn(10L);
    when(processService.split(10L)).thenReturn(3);
    stubDocument(10L, 20L, KnowledgeBaseType.DOCUMENT_SEARCH);

    DocumentIngestionFacade.DocumentIngestionResult result =
        facade.upload(file, "note", null, DocumentAccessScope.PRIVATE, null);

    assertThat(result.documentId()).isEqualTo(10L);
    assertThat(result.segmentCount()).isEqualTo(3);
    verify(processService).split(10L);
  }

  @Test
  @DisplayName("DATA_QUERY 上传后跳过 split")
  void uploadSkipsSplitForDataQuery() {
    MultipartFile file = new MockMultipartFile("file", "sales.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2});
    when(processService.upload(any(), any(), any(), any(), any())).thenReturn(11L);
    stubDocument(11L, 21L, KnowledgeBaseType.DATA_QUERY);

    DocumentIngestionFacade.DocumentIngestionResult result =
        facade.upload(file, "sales", null, DocumentAccessScope.PRIVATE, null);

    assertThat(result.documentId()).isEqualTo(11L);
    assertThat(result.segmentCount()).isZero();
    verify(processService, never()).split(any());
  }

  @Test
  @DisplayName("DATA_QUERY 新版本上传也跳过 split")
  void uploadVersionSkipsSplitForDataQuery() {
    MultipartFile file = new MockMultipartFile("file", "sales-v2.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{3, 4});
    when(processService.uploadNewVersion(eq(11L), any(), any())).thenReturn(22L);
    stubDocument(11L, 22L, KnowledgeBaseType.DATA_QUERY);

    DocumentIngestionFacade.DocumentIngestionResult result =
        facade.uploadVersion(11L, file, "v2");

    assertThat(result.versionId()).isEqualTo(22L);
    assertThat(result.segmentCount()).isZero();
    verify(processService, never()).split(any());
  }

  private void stubDocument(Long docId, Long versionId, KnowledgeBaseType kbType) {
    KnowledgeBaseEntity doc = new KnowledgeBaseEntity();
    doc.setId(docId);
    doc.setCurrentVersionId(versionId);
    doc.setKnowledgeBaseType(kbType);
    when(documentService.getById(docId)).thenReturn(doc);

    KnowledgeBaseVersionEntity version = new KnowledgeBaseVersionEntity();
    version.setVersionId(versionId);
    when(versionService.getById(versionId)).thenReturn(version);
  }
}
