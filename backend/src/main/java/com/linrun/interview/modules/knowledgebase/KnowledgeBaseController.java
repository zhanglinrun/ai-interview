package com.linrun.interview.modules.knowledgebase;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.common.web.AttachmentResponseBuilder;
import com.linrun.interview.modules.knowledgebase.constant.DocumentStatus;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseVersionDTO;
import com.linrun.interview.modules.knowledgebase.constant.KnowledgeBaseType;
import com.linrun.interview.modules.knowledgebase.model.DocumentSplitParam;
import com.linrun.interview.modules.knowledgebase.model.QueryRequest;
import com.linrun.interview.modules.knowledgebase.model.QueryResponse;
import com.linrun.interview.modules.knowledgebase.model.RagEvalRequest;
import com.linrun.interview.modules.knowledgebase.model.RagEvalResponse;
import com.linrun.interview.modules.knowledgebase.model.RagQueryTraceDTO;
import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.modules.knowledgebase.service.DocumentProcessService;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseDataTableService;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseListService;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryService;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeDocumentService;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeDocumentVersionService;
import com.linrun.interview.modules.knowledgebase.service.RagEvaluationService;
import com.linrun.interview.modules.knowledgebase.service.RagQueryTraceService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "知识库上传、下载、查询、分类与向量化")
public class KnowledgeBaseController {

    private final DocumentProcessService documentProcessService;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeDocumentVersionService versionService;
    private final KnowledgeBaseQueryService queryService;
    private final RagEvaluationService ragEvaluationService;
    private final RagQueryTraceService ragQueryTraceService;
    private final KnowledgeBaseDataTableService dataTableService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;

    /**
     * 获取所有知识库列表
     */
    @GetMapping("/api/knowledgebase/list")
    public Result<List<KnowledgeBaseListItemDTO>> getAllKnowledgeBases(
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "docStatus", required = false) String docStatus) {

        DocumentStatus status = null;
        if (docStatus != null && !docStatus.isBlank()) {
            try {
                status = DocumentStatus.valueOf(docStatus.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Result.error("无效的文档状态: " + docStatus);
            }
        }

        return Result.success(listService.listKnowledgeBases(status, sortBy));
    }

    /**
     * 获取知识库详情
     */
    @GetMapping("/api/knowledgebase/{id}")
    public Result<KnowledgeBaseListItemDTO> getKnowledgeBase(@PathVariable Long id) {
        return listService.getKnowledgeBase(id)
                .map(Result::success)
                .orElse(Result.error("知识库不存在"));
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/api/knowledgebase/{id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable Long id) {
        knowledgeDocumentService.removeDocumentWithSegments(id);
        return Result.success(null);
    }

    /**
     * 基于知识库回答问题（支持多知识库）
     */
    @PostMapping("/api/knowledgebase/query")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
    public Result<QueryResponse> queryKnowledgeBase(@Valid @RequestBody QueryRequest request) {
        return Result.success(queryService.queryKnowledgeBase(request));
    }

    /**
     * 基于知识库回答问题（流式SSE，支持多知识库）
     */
    @PostMapping(value = "/api/knowledgebase/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Flux<String> queryKnowledgeBaseStream(@Valid @RequestBody QueryRequest request) {
        log.debug("收到知识库流式查询请求: kbIds={}, question={}, 线程: {} (虚拟线程: {})",
            request.knowledgeBaseIds(), request.question(), Thread.currentThread(), Thread.currentThread().isVirtual());
        return queryService.answerQuestionStream(request.knowledgeBaseIds(), request.question());
    }

    /**
     * RAG 检索评测：计算 Hit@K / MRR / NDCG，不调用生成模型。
     */
    @PostMapping("/api/knowledgebase/evaluate-retrieval")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<RagEvalResponse> evaluateRetrieval(@Valid @RequestBody RagEvalRequest request) {
        return Result.success(ragEvaluationService.evaluate(request));
    }

    /**
     * 最近 RAG 查询 Trace：查看改写、路由、召回、排序和最终引用。
     */
    @GetMapping("/api/knowledgebase/traces")
    public Result<List<RagQueryTraceDTO>> listTraces(
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        return Result.success(ragQueryTraceService.listRecent(limit));
    }

    @GetMapping("/api/knowledgebase/traces/{traceId}")
    public Result<RagQueryTraceDTO> getTrace(@PathVariable String traceId) {
        return Result.success(ragQueryTraceService.get(traceId));
    }

    // ========== 分类管理 API ==========

    /**
     * 获取所有分类
     */
    @GetMapping("/api/knowledgebase/categories")
    public Result<List<String>> getAllCategories() {
        return Result.success(listService.getAllCategories());
    }

    /**
     * 根据分类获取知识库列表
     */
    @GetMapping("/api/knowledgebase/category/{category}")
    public Result<List<KnowledgeBaseListItemDTO>> getByCategory(@PathVariable String category) {
        return Result.success(listService.listByCategory(category));
    }

    /**
     * 获取未分类的知识库
     */
    @GetMapping("/api/knowledgebase/uncategorized")
    public Result<List<KnowledgeBaseListItemDTO>> getUncategorized() {
        return Result.success(listService.listByCategory(null));
    }

    /**
     * 更新知识库分类
     */
    @PutMapping("/api/knowledgebase/{id}/category")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        listService.updateCategory(id, body.get("category"));
        return Result.success(null);
    }

    // ========== 上传下载 API ==========

    /**
     * 上传知识库文件
     * <p>对齐 know-engine：upload 同步完成解析（UPLOADED→CONVERTING→CONVERTED），
     * split 由调用方单独触发（切块后发 DocumentChunkedEvent 异步向量化）。
     */
    @PostMapping(value = "/api/knowledgebase/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
    public Result<Map<String, Object>> uploadKnowledgeBase(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "knowledgeBaseType", required = false, defaultValue = "DOCUMENT_SEARCH")
            String knowledgeBaseType) {
        KnowledgeBaseType type = parseKnowledgeBaseType(knowledgeBaseType);
        return Result.success(uploadSingle(file, name, category, type));
    }

    /**
     * 批量上传知识库文件
     * <p>逐个复用单文件上传逻辑，单个文件失败不影响其余，最后汇总。
     */
    @PostMapping(value = "/api/knowledgebase/upload/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Map<String, Object>> uploadKnowledgeBaseBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "category", required = false) String category) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请至少选择一个文件");
        }
        log.info("收到批量知识库上传请求: 文件数={}, category={}", files.size(), category);
        long startTime = System.currentTimeMillis();

        List<Map<String, Object>> items = new ArrayList<>();
        int success = 0;
        int failed = 0;

        for (MultipartFile file : files) {
            String fileName = file != null ? file.getOriginalFilename() : null;
            try {
                Map<String, Object> result = uploadSingle(file, null, category, KnowledgeBaseType.DOCUMENT_SEARCH);
                Object kbObj = result.get("knowledgeBase");
                if (kbObj instanceof Map<?, ?> kbMap) {
                    Object idObj = kbMap.get("id");
                    if (idObj instanceof Number idNum) {
                        documentProcessService.split(idNum.longValue());
                    }
                }
                success++;
                items.add(Map.of(
                    "filename", fileName != null ? fileName : "",
                    "status", "success",
                    "duplicate", false,
                    "detail", result
                ));
            } catch (Exception e) {
                failed++;
                log.warn("批量上传中单个文件失败: {}, error={}", fileName, e.getMessage(), e);
                items.add(Map.of(
                    "filename", fileName != null ? fileName : "",
                    "status", "failed",
                    "error", e.getMessage() != null ? e.getMessage() : "未知错误"
                ));
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("批量知识库上传完成: 总数={}, 成功={}, 失败={}, 耗时={}ms",
            files.size(), success, failed, totalTime);

        return Result.success(Map.of(
            "total", files.size(),
            "success", success,
            "failed", failed,
            "duplicate", 0,
            "items", items
        ));
    }

    @GetMapping("/api/knowledgebase/{id}/data/preview")
    public Result<KnowledgeBaseDataTableService.PreviewResponse> previewDataTable(
            @PathVariable Long id,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "50") int size) {
        return Result.success(dataTableService.preview(UserContext.requireUserId(), id, page, size));
    }

    /**
     * 单文件上传内部逻辑：仅 upload（解析落库至 CONVERTED），不自动 split。
     */
    private Map<String, Object> uploadSingle(MultipartFile file, String name, String category,
                                             KnowledgeBaseType knowledgeBaseType) {
        String fileName = file.getOriginalFilename();
        log.info("收到知识库上传请求: {}, 大小: {} bytes, category={}, type={}",
            fileName, file.getSize(), category, knowledgeBaseType);

        Long docId = documentProcessService.upload(file, name, category, knowledgeBaseType);

        KnowledgeBaseEntity entity = EntityQueries.byUserAndId(
            knowledgeBaseEntityMapper, UserContext.requireUserId(), docId,
            KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "上传后知识库记录丢失"));

        Map<String, Object> result = new HashMap<>();
        result.put("knowledgeBase", Map.of(
            "id", docId,
            "name", entity.getName(),
            "category", entity.getCategory() != null ? entity.getCategory() : "",
            "fileSize", file.getSize(),
            "contentLength", 0,
            "docStatus", entity.getDocStatus().name()
        ));
        result.put("storage", Map.of(
            "fileKey", entity.getStorageKey() != null ? entity.getStorageKey() : "",
            "fileUrl", entity.getStorageUrl() != null ? entity.getStorageUrl() : ""
        ));
        result.put("duplicate", false);
        return result;
    }

    /**
     * 下载知识库文件
     */
    @GetMapping("/api/knowledgebase/{id}/download")
    public ResponseEntity<byte[]> downloadKnowledgeBase(@PathVariable Long id) {
        var entity = listService.getEntityForDownload(id);
        byte[] fileContent = listService.downloadFile(id);

        return AttachmentResponseBuilder.attachment(
                entity.getOriginalFilename(),
                entity.getContentType(),
                fileContent);
    }

    // ========== 搜索 API ==========

    /**
     * 搜索知识库
     */
    @GetMapping("/api/knowledgebase/search")
    public Result<List<KnowledgeBaseListItemDTO>> search(@RequestParam("keyword") String keyword) {
        return Result.success(listService.search(keyword));
    }

    // ========== 统计 API ==========

    /**
     * 获取知识库统计信息
     */
    @GetMapping("/api/knowledgebase/stats")
    public Result<KnowledgeBaseStatsDTO> getStatistics() {
        return Result.success(listService.getStatistics());
    }

    // ========== 向量化管理 API ==========

    /**
     * 重新向量化知识库（手动重试）
     * <p>走新链路 rechunk：删当前版本 segment + 清 ES 向量 + 降状态，再 split 重新发事件向量化。
     */
    @PostMapping("/api/knowledgebase/{id}/revectorize")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Void> revectorize(@PathVariable Long id) {
        documentProcessService.rechunk(id);
        return Result.success(null);
    }

    /**
     * 按策略重新切块（可选参数，默认 BROTHER）。
     */
    @PostMapping("/api/knowledgebase/{id}/split")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
    public Result<Map<String, Object>> splitDocument(@PathVariable Long id,
                                                     @RequestBody(required = false) DocumentSplitParam splitParam) {
        int segmentCount = documentProcessService.split(id, splitParam);
        return Result.success(Map.of("segmentCount", segmentCount));
    }

    // ========== 版本管理 API ==========

    /**
     * 查询知识库所有版本（降序，最新在前）。
     */
    @GetMapping("/api/knowledgebase/{id}/versions")
    public Result<List<KnowledgeBaseVersionDTO>> listVersions(@PathVariable Long id) {
        return Result.success(versionService.listByDocId(id).stream()
            .map(KnowledgeBaseVersionDTO::from)
            .toList());
    }

    /**
     * 上传新版本（版本号自动递增，旧当前版本即时失效）。
     */
    @PostMapping(value = "/api/knowledgebase/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
    public Result<Map<String, Object>> uploadNewVersion(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "changelog", required = false) String changelog) {
        Long versionId = documentProcessService.uploadNewVersion(id, file, changelog);
        return Result.success(Map.of(
            "docId", id,
            "versionId", versionId,
            "message", "新版本上传完成（CONVERTED），请调用 split 触发切块与向量化"
        ));
    }

    /**
     * 切换当前激活版本（热切换：已向量化版本零重建，未向量化版本先激活再切换）。
     */
    @PostMapping("/api/knowledgebase/{id}/versions/{versionId}/switch")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Void> switchVersion(@PathVariable Long id, @PathVariable Long versionId) {
        documentProcessService.switchVersion(id, versionId);
        return Result.success(null);
    }

    /**
     * 失效版本：清 ES 向量 + segment 降 STORED + 版本降 CHUNKED（保留数据可再激活）。
     */
    @PostMapping("/api/knowledgebase/{id}/versions/{versionId}/deactivate")
    public Result<Void> deactivateVersion(@PathVariable Long id, @PathVariable Long versionId) {
        knowledgeDocumentService.deactivateVersion(versionId);
        return Result.success(null);
    }

    /**
     * 激活版本：重新向量化 STORED 分段 + 版本升 VECTOR_STORED + 同步主表当前版本。
     */
    @PostMapping("/api/knowledgebase/{id}/versions/{versionId}/activate")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Void> activateVersion(@PathVariable Long id, @PathVariable Long versionId) {
        knowledgeDocumentService.activateVersion(versionId);
        return Result.success(null);
    }

    private static KnowledgeBaseType parseKnowledgeBaseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return KnowledgeBaseType.DOCUMENT_SEARCH;
        }
        try {
            return KnowledgeBaseType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "无效的知识库类型: " + raw + "，可选 DOCUMENT_SEARCH / DATA_QUERY");
        }
    }

}
