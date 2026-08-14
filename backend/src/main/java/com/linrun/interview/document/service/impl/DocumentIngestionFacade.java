package com.linrun.interview.document.service.impl;

import com.linrun.interview.document.service.DocumentProcessService;
import com.linrun.interview.document.service.KnowledgeDocumentService;
import com.linrun.interview.document.service.KnowledgeDocumentVersionService;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.document.constant.DocumentAccessScope;
import com.linrun.interview.document.constant.KnowledgeBaseType;
import com.linrun.interview.document.vo.DocumentParseTaskDTO;
import com.linrun.interview.document.entity.DocumentParseTaskEntity;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 文档领域的单一应用入口。
 *
 * <p>Controller 不再自行拼接上传、切片和版本状态；这里复用已有底层 parser、splitter、
 * embedding 和 compensation 组件，把一次用户操作收敛成可审计的命令。</p>
 */
@Service
@RequiredArgsConstructor
public class DocumentIngestionFacade {

    private final DocumentProcessService processService;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeDocumentVersionService versionService;
    private final DocumentParseTaskService parseTaskService;
    private final KnowledgeBaseListService listService;

    public DocumentIngestionResult upload(MultipartFile file, String title, String category,
                                          DocumentAccessScope scope, LocalDate expireDate) {
        Long documentId = processService.upload(file, title, category, scope, expireDate);
        return snapshot(documentId, maybeSplit(documentId));
    }

    public DocumentIngestionResult uploadVersion(Long documentId, MultipartFile file, String changelog) {
        Long versionId = processService.uploadNewVersion(documentId, file, changelog);
        int segmentCount = maybeSplit(documentId);
        DocumentIngestionResult result = snapshot(documentId, segmentCount);
        return new DocumentIngestionResult(result.documentId(), versionId, result.status(), segmentCount);
    }

    private int maybeSplit(Long documentId) {
        KnowledgeBaseEntity document = documentService.getById(documentId);
        if (document != null && document.getKnowledgeBaseType() == KnowledgeBaseType.DATA_QUERY) {
            return 0;
        }
        return processService.split(documentId);
    }

    public DocumentIngestionResult reindex(Long documentId) {
        int segmentCount = processService.rechunk(documentId);
        return snapshot(documentId, segmentCount);
    }

    public Optional<DocumentParseTaskDTO> latestTask(Long documentId, Long versionId) {
        DocumentParseTaskEntity task = parseTaskService.findLatest(
            UserContext.requireUserId(), documentId, versionId).orElse(null);
        return task == null ? Optional.empty() : Optional.of(DocumentParseTaskDTO.from(task));
    }

    /** 返回当前用户可见的版本摘要，不泄露转换正文和对象存储地址。 */
    public List<DocumentVersionView> listVersions(Long documentId) {
        KnowledgeBaseEntity document = listService.getKnowledgeBaseEntity(documentId)
            .orElseThrow(() -> new com.linrun.interview.common.exception.BusinessException(
                com.linrun.interview.common.exception.ErrorCode.NOT_FOUND, "文档不存在"));
        return versionService.listByDocId(document.getId()).stream()
            .map(version -> new DocumentVersionView(
                version.getVersionId(), version.getVersion(), version.getStatus() == null
                    ? null : version.getStatus().name(), version.getChangelog(),
                version.getEmbeddingAttempt(), version.getEmbeddingTerminalFailure(),
                version.getCreatedAt(), version.getUpdatedAt()))
            .toList();
    }

    public void delete(Long documentId) {
        documentService.removeDocumentWithSegments(documentId);
    }

    private DocumentIngestionResult snapshot(Long documentId, int segmentCount) {
        KnowledgeBaseEntity document = documentService.getById(documentId);
        KnowledgeBaseVersionEntity version = document == null || document.getCurrentVersionId() == null
            ? null : versionService.getById(document.getCurrentVersionId());
        return new DocumentIngestionResult(
            documentId,
            version == null ? null : version.getVersionId(),
            document == null || document.getDocStatus() == null ? null : document.getDocStatus().name(),
            segmentCount);
    }

    public record DocumentIngestionResult(
        Long documentId,
        Long versionId,
        String status,
        int segmentCount
    ) {}

    public record DocumentVersionView(
        Long versionId,
        String version,
        String status,
        String changelog,
        Integer embeddingAttempt,
        Boolean embeddingTerminalFailure,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}
}
