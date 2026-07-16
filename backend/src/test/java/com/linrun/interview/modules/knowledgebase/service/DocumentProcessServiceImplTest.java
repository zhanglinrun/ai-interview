package com.linrun.interview.modules.knowledgebase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.infrastructure.file.ContentTypeDetectionService;
import com.linrun.interview.infrastructure.file.FileHashService;
import com.linrun.interview.infrastructure.file.FileStorageService;
import com.linrun.interview.infrastructure.file.FileValidationService;
import com.linrun.interview.modules.knowledgebase.constant.DocumentStatus;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.modules.knowledgebase.service.parse.FileProcessServiceFactory;
import com.linrun.interview.modules.knowledgebase.service.parse.FileTypeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ObjectMapper objectMapper;

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
        version.setConvertedContent("# test");
        return version;
    }
}
