package com.linrun.interview.document.job;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.constant.KnowledgeBaseType;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.document.service.DocumentCleanupService;
import com.linrun.interview.document.service.DocumentProcessService;
import com.linrun.interview.document.service.KnowledgeDocumentService;
import com.linrun.interview.document.service.KnowledgeDocumentVersionService;
import com.linrun.interview.document.service.KnowledgeSegmentService;
import com.linrun.interview.document.service.impl.VectorizationTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("文档解析补偿")
class DocumentConvertCompensationJobTest {

  @Mock private KnowledgeDocumentService knowledgeDocumentService;
  @Mock private KnowledgeDocumentVersionService versionService;
  @Mock private KnowledgeSegmentService segmentService;
  @Mock private KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;
  @Mock private DocumentCleanupService documentCleanupService;
  @Mock private VectorizationTaskService vectorizationTaskService;
  @Mock private DocumentProcessService documentProcessService;

  @InjectMocks private DocumentCompensationJob job;

  @Test
  @DisplayName("陈旧 UPLOADED 文档会补跑解析和切块")
  void retriesStaleUploadedDocument() {
    KnowledgeBaseEntity doc = new KnowledgeBaseEntity();
    doc.setId(33L);
    doc.setDocStatus(DocumentStatus.UPLOADED);
    doc.setKnowledgeBaseType(KnowledgeBaseType.DOCUMENT_SEARCH);
    doc.setUploadedAt(LocalDateTime.now().minusMinutes(5));
    when(knowledgeBaseEntityMapper.selectList(any(Wrapper.class))).thenReturn(List.of(doc));

    job.runConvertCompensation();

    verify(documentProcessService).processAcceptedDocument(eq(33L), eq(true));
  }
}
