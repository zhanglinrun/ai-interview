package com.linrun.interview.document.controller;

import com.linrun.interview.common.result.Result;
import com.linrun.interview.document.constant.DocumentAccessScope;
import com.linrun.interview.document.vo.DocumentParseTaskDTO;
import com.linrun.interview.document.service.impl.DocumentIngestionFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/** 文档 v1 命令接口：上传、版本、重建索引和任务查询。 */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "Document Ingestion")
public class DocumentV1Controller {

    private final DocumentIngestionFacade ingestionFacade;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传并处理文档")
    public Result<DocumentIngestionFacade.DocumentIngestionResult> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false) String title,
        @RequestParam(required = false) String category,
        @RequestParam(defaultValue = "PRIVATE") String accessibleBy,
        @RequestParam(required = false) LocalDate expireDate) {
        return Result.success(ingestionFacade.upload(
            file, title, category, DocumentAccessScope.from(accessibleBy), expireDate));
    }

    @PostMapping(value = "/{documentId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文档新版本")
    public Result<DocumentIngestionFacade.DocumentIngestionResult> uploadVersion(
        @PathVariable Long documentId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false) String changelog) {
        return Result.success(ingestionFacade.uploadVersion(documentId, file, changelog));
    }

    @GetMapping("/{documentId}/versions")
    @Operation(summary = "查询文档版本")
    public Result<List<DocumentIngestionFacade.DocumentVersionView>> versions(
        @PathVariable Long documentId) {
        return Result.success(ingestionFacade.listVersions(documentId));
    }

    @PostMapping("/{documentId}/reindex")
    @Operation(summary = "重新切片并向量化")
    public Result<DocumentIngestionFacade.DocumentIngestionResult> reindex(
        @PathVariable Long documentId) {
        return Result.success(ingestionFacade.reindex(documentId));
    }

    @GetMapping({"/{documentId}/versions/{versionId}/task",
        "/{documentId}/versions/{versionId}/tasks"})
    @Operation(summary = "查询文档处理任务")
    public Result<DocumentParseTaskDTO> latestTask(
        @PathVariable Long documentId,
        @PathVariable Long versionId) {
        return ingestionFacade.latestTask(documentId, versionId)
            .map(Result::success)
            .orElse(Result.error("处理任务不存在"));
    }

    @DeleteMapping("/{documentId}")
    @Operation(summary = "删除文档及其索引")
    public Result<Void> delete(@PathVariable Long documentId) {
        ingestionFacade.delete(documentId);
        return Result.success();
    }
}
