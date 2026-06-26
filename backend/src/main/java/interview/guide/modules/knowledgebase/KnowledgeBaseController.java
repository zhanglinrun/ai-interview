package interview.guide.modules.knowledgebase;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.common.security.UserContext;
import interview.guide.common.web.AttachmentResponseBuilder;
import interview.guide.modules.knowledgebase.constant.DocumentStatus;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.DocumentProcessService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.KnowledgeDocumentService;
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
    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 获取所有知识库列表
     */
    @GetMapping("/api/knowledgebase/list")
    public Result<List<KnowledgeBaseListItemDTO>> getAllKnowledgeBases(
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "docStatus", required = false) String docStatus,
            @RequestParam(value = "vectorStatus", required = false) String vectorStatus) {

        DocumentStatus status = null;
        String raw = docStatus != null && !docStatus.isBlank() ? docStatus : vectorStatus;
        if (raw != null && !raw.isBlank()) {
            try {
                status = DocumentStatus.valueOf(raw.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Result.error("无效的文档状态: " + raw);
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
     * <p>新链路：upload（解析落库）+ split（切块发事件触发异步向量化），返回结构与旧接口兼容。
     */
    @PostMapping(value = "/api/knowledgebase/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
    public Result<Map<String, Object>> uploadKnowledgeBase(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category) {
        return Result.success(uploadSingle(file, name, category));
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
                Map<String, Object> result = uploadSingle(file, null, category);
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

    /**
     * 单文件上传内部逻辑：upload + split，返回与旧接口兼容的结构。
     */
    private Map<String, Object> uploadSingle(MultipartFile file, String name, String category) {
        String fileName = file.getOriginalFilename();
        log.info("收到知识库上传请求: {}, 大小: {} bytes, category: {}", fileName, file.getSize(), category);

        Long docId = documentProcessService.upload(file, name, category);
        documentProcessService.split(docId, "TITLE", null, null);

        KnowledgeBaseEntity entity = knowledgeBaseRepository
            .findByUserIdAndId(UserContext.requireUserId(), docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "上传后知识库记录丢失"));

        Map<String, Object> result = new HashMap<>();
        result.put("knowledgeBase", Map.of(
            "id", docId,
            "name", entity.getName(),
            "category", entity.getCategory() != null ? entity.getCategory() : "",
            "fileSize", file.getSize(),
            "contentLength", 0,
            "vectorStatus", "PENDING"
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

}
