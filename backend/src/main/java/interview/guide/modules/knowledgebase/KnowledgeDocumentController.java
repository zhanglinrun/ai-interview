package interview.guide.modules.knowledgebase;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import interview.guide.modules.knowledgebase.service.DocumentProcessService;
import interview.guide.modules.knowledgebase.service.KnowledgeDocumentService;
import interview.guide.modules.knowledgebase.service.KnowledgeDocumentVersionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档管理控制器（对齐 know-engine KnowledgeDocumentController）。
 *
 * <p>提供版本管理 + 切块编排端点（{@code /api/document/*}），与旧 {@link KnowledgeBaseController}
 *（{@code /api/knowledgebase/*}）并存，前端逐步迁移。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "知识库文档管理", description = "版本管理、切块、向量化编排（对齐 know-engine）")
public class KnowledgeDocumentController {

    private final DocumentProcessService documentProcessService;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeDocumentVersionService versionService;

    /**
     * 上传文档（创建首个版本 v1.0.0）。
     */
    @PostMapping(value = "/api/document/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
    public Result<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "category", required = false) String category) {
        Long docId = documentProcessService.upload(file, title, category);
        Map<String, Object> result = new HashMap<>();
        result.put("docId", docId);
        result.put("status", "CONVERTED");
        result.put("message", "上传成功，请调用 /api/document/split 触发切块向量化");
        return Result.success(result);
    }

    /**
     * 上传新版本（版本号自动递增）。
     */
    @PostMapping(value = "/api/document/upload-version", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
    public Result<Map<String, Object>> uploadNewVersion(
            @RequestParam("docId") Long docId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "changelog", required = false) String changelog) {
        Long versionId = documentProcessService.uploadNewVersion(docId, file, changelog);
        Map<String, Object> result = new HashMap<>();
        result.put("docId", docId);
        result.put("versionId", versionId);
        result.put("status", "CONVERTED");
        result.put("message", "新版本上传成功，请调用 /api/document/split 触发切块向量化");
        return Result.success(result);
    }

    /**
     * 查询文档所有版本（降序，最新在前）。
     */
    @GetMapping("/api/document/versions/{docId}")
    public Result<List<KnowledgeBaseVersionEntity>> listVersions(@PathVariable Long docId) {
        return Result.success(versionService.listByDocId(docId));
    }

    /**
     * 切块：按切分类型切块落 segment 表，状态置 CHUNKED，异步触发向量化。
     */
    @PostMapping("/api/document/split/{docId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<Map<String, Object>> split(
            @PathVariable Long docId,
            @RequestParam(value = "splitType", defaultValue = "TITLE") String splitType,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "overlap", required = false) Integer overlap) {
        int segmentCount = documentProcessService.split(docId, splitType, chunkSize, overlap);
        Map<String, Object> result = new HashMap<>();
        result.put("docId", docId);
        result.put("segmentCount", segmentCount);
        result.put("status", "CHUNKED");
        result.put("message", "切块完成，向量化已异步触发");
        return Result.success(result);
    }

    /**
     * 失效版本：清 ES 向量 + segment 降 STORED + 版本降 CHUNKED。
     */
    @PostMapping("/api/document/deactivate-version")
    public Result<Void> deactivateVersion(@RequestParam("versionId") Long versionId) {
        knowledgeDocumentService.deactivateVersion(versionId);
        return Result.success(null);
    }

    /**
     * 激活版本：重新向量化 STORED 分段 + 版本升 VECTOR_STORED。
     */
    @PostMapping("/api/document/activate-version")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Void> activateVersion(@RequestParam("versionId") Long versionId) {
        knowledgeDocumentService.activateVersion(versionId);
        return Result.success(null);
    }
}
