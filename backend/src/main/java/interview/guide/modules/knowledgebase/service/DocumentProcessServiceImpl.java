package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.UserContext;
import interview.guide.infrastructure.file.ContentTypeDetectionService;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.knowledgebase.constant.DocumentStatus;
import interview.guide.modules.knowledgebase.constant.FileType;
import interview.guide.modules.knowledgebase.constant.MetadataKeyConstant;
import interview.guide.modules.knowledgebase.constant.SegmentStatus;
import interview.guide.modules.knowledgebase.event.DocumentChunkedEvent;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.parse.FileProcessService;
import interview.guide.modules.knowledgebase.service.parse.FileProcessServiceFactory;
import interview.guide.modules.knowledgebase.service.parse.FileTypeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档处理编排实现（对齐 know-engine DocumentProcessServiceImpl）。
 *
 * <p>编排 upload→解析→落库版本、split→切块→落 segment→发事件、embedAndStore→向量化。
 *
 * <p>与 know-engine 差异（遵守 ai-interview AGENTS.md）：
 * <ul>
 *   <li>用户隔离用 {@link UserContext#requireUserId()}（非 accessibleBy/uploadUser 字符串）。</li>
 *   <li>解析产物 Markdown 直接存版本表 {@code convertedContent}（Lob），split 时直接取，省存储往返。</li>
 *   <li>切块固定用 {@link MarkdownHeaderBrotherTextSplitter}（splitType=TITLE/SMART），不引入 DocumentSplitterFactory 多策略。</li>
 *   <li>{@code Assert} → {@link BusinessException}；不加 {@code @DistributeLock}（保持简单，可后续加）。</li>
 *   <li>事务边界：upload/split 含存储/解析外部调用，不加 {@code @Transactional}；DB 写操作走各 Service 自身事务。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessServiceImpl implements DocumentProcessService {

    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
    private static final String INITIAL_VERSION = "1.0.0";

    private final FileProcessServiceFactory fileProcessServiceFactory;
    private final FileTypeResolver fileTypeResolver;
    private final FileStorageService storageService;
    private final FileHashService fileHashService;
    private final FileValidationService fileValidationService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentVersionService versionService;
    private final KnowledgeSegmentService segmentService;
    private final KnowledgeBaseChunkingService chunkingService;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final VectorStoreService vectorStoreService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    public Long upload(MultipartFile file, String title, String category) {
        Long userId = UserContext.requireUserId();
        String fileName = file.getOriginalFilename();

        // 1. 校验
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");
        String contentType = contentTypeDetectionService.detectContentType(file);
        if (!fileValidationService.isKnowledgeBaseMimeType(contentType)
            && !fileValidationService.isMarkdownExtension(fileName)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的文件类型，仅支持 PDF、DOCX、DOC、TXT、MD 等");
        }

        // 2. 内容哈希去重（跨版本）
        String contentHash = fileHashService.calculateHash(file);
        if (versionService.findByContentHash(contentHash).isPresent()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档内容已存在，请勿重复上传");
        }

        // 3. 存原始文件到 RustFS
        String storageKey = storageService.uploadKnowledgeBase(file);
        String docUrl = storageService.getFileUrl(storageKey);

        // 4. 解析为 Markdown
        FileType fileType = fileTypeResolver.resolve(fileName, contentType);
        FileProcessService processor = fileProcessServiceFactory.get(fileType);
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                "读取上传文件失败: " + e.getMessage(), e);
        }
        String markdown = processor.processDocument(fileBytes, fileName);
        if (markdown == null || markdown.isBlank()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文件解析结果为空");
        }

        // 5. 落库知识库主表（状态 CONVERTED，待 split）
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setUserId(userId);
        entity.setFileHash(contentHash);
        entity.setName(title != null && !title.isBlank() ? title : fileName);
        entity.setCategory(category);
        entity.setOriginalFilename(fileName);
        entity.setFileSize(file.getSize());
        entity.setContentType(contentType);
        entity.setStorageKey(storageKey);
        entity.setStorageUrl(docUrl);
        entity.setDocStatus(DocumentStatus.CONVERTED);
        entity = knowledgeBaseRepository.save(entity);

        // 6. 创建初始版本 v1.0.0（Markdown 存 convertedContent）
        KnowledgeBaseVersionEntity version = new KnowledgeBaseVersionEntity();
        version.setDocId(entity.getId());
        version.setVersion(INITIAL_VERSION);
        version.setDocUrl(docUrl);
        version.setContentHash(contentHash);
        version.setStatus(DocumentStatus.CONVERTED);
        version.setUploadUser(String.valueOf(userId));
        version.setConvertedContent(markdown);
        version = versionService.save(version);

        // 7. 主表回填 currentVersionId
        entity.setCurrentVersionId(version.getVersionId());
        knowledgeBaseRepository.save(entity);

        log.info("知识库上传完成: docId={}, versionId={}, markdownChars={}",
            entity.getId(), version.getVersionId(), markdown.length());
        return entity.getId();
    }

    @Override
    public Long uploadNewVersion(Long docId, MultipartFile file, String changelog) {
        Long userId = UserContext.requireUserId();
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findByUserIdAndId(userId, docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId));

        String fileName = file.getOriginalFilename();
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");
        String contentType = contentTypeDetectionService.detectContentType(file);

        // 版本号自增：取当前最新版本号 +1
        KnowledgeBaseVersionEntity latest = versionService.findLatestByDocId(docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库无版本记录: " + docId));
        String newVersion = nextVersion(latest.getVersion());

        String contentHash = fileHashService.calculateHash(file);
        if (versionService.findByContentHash(contentHash).isPresent()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档内容已存在，请勿重复上传");
        }

        String storageKey = storageService.uploadKnowledgeBase(file);
        String docUrl = storageService.getFileUrl(storageKey);

        FileType fileType = fileTypeResolver.resolve(fileName, contentType);
        FileProcessService processor = fileProcessServiceFactory.get(fileType);
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                "读取上传文件失败: " + e.getMessage(), e);
        }
        String markdown = processor.processDocument(fileBytes, fileName);
        if (markdown == null || markdown.isBlank()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文件解析结果为空");
        }

        KnowledgeBaseVersionEntity version = new KnowledgeBaseVersionEntity();
        version.setDocId(docId);
        version.setVersion(newVersion);
        version.setDocUrl(docUrl);
        version.setContentHash(contentHash);
        version.setStatus(DocumentStatus.CONVERTED);
        version.setUploadUser(String.valueOf(userId));
        version.setChangelog(changelog);
        version.setConvertedContent(markdown);
        version = versionService.save(version);

        // 即时失效旧当前版本：若旧版本已向量化，主动清 ES 向量 + segment 降 STORED，不等补偿任务。
        // 失败仅告警，不阻断新版本上传——旧版本残留向量化状态可由补偿任务兜底清理。
        if (latest.getStatus() == DocumentStatus.VECTOR_STORED) {
            try {
                knowledgeDocumentService.deactivateVersion(latest.getVersionId());
            } catch (Exception e) {
                log.warn("新版本上传时失效旧版本失败，已继续上传新版本，旧版本留待补偿任务清理: docId={}, oldVersionId={}, error={}",
                    docId, latest.getVersionId(), e.getMessage(), e);
            }
        }

        // 主表指向新版本，状态降回 CONVERTED（待 split 重新向量化）
        entity.setCurrentVersionId(version.getVersionId());
        entity.setDocStatus(DocumentStatus.CONVERTED);
        knowledgeBaseRepository.save(entity);

        log.info("知识库新版本上传完成: docId={}, version={}, 旧版本即时失效已尝试",
            docId, newVersion);
        return version.getVersionId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int split(Long docId, String splitType, Integer chunkSize, Integer overlap) {
        Long userId = UserContext.requireUserId();
        log.info("切块请求: docId={}, splitType={}, chunkSize={}, overlap={}",
            docId, splitType, chunkSize, overlap);
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findByUserIdAndId(userId, docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId));

        if (entity.getDocStatus() == DocumentStatus.CHUNKED) {
            long count = segmentService.countByDocumentVersion(entity.getCurrentVersionId());
            log.info("文档已切块，返回现有分段数: docId={}, count={}", docId, count);
            return (int) count;
        }
        if (entity.getDocStatus() != DocumentStatus.CONVERTED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "文档状态不为 CONVERTED，无法切块，当前状态: " + entity.getDocStatus());
        }

        KnowledgeBaseVersionEntity version = versionService.getById(entity.getCurrentVersionId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在"));
        String markdown = version.getConvertedContent();
        if (markdown == null || markdown.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "转换后内容为空，请重新上传");
        }

        // 切块（复用 KnowledgeBaseChunkingService，内部用 MarkdownHeaderBrotherTextSplitter）
        List<TextSegment> segments = chunkingService.split(markdown);
        log.info("切块完成: docId={}, segmentCount={}", docId, segments.size());

        // 转 segment 实体并落库
        List<KnowledgeBaseSegmentEntity> segmentEntities = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            var metadata = seg.metadata();
            Map<String, String> metadataMap = new HashMap<>();
            metadata.toMap().forEach((k, v) -> metadataMap.put(k, v == null ? "" : String.valueOf(v)));
            metadataMap.put(MetadataKeyConstant.DOC_ID, String.valueOf(docId));
            metadataMap.put(MetadataKeyConstant.FILE_NAME, entity.getName());
            metadataMap.put(MetadataKeyConstant.URL, version.getDocUrl());
            metadataMap.put(MetadataKeyConstant.VERSION, String.valueOf(version.getVersionId()));
            metadataMap.put(MetadataKeyConstant.ACCESSIBLE_BY, String.valueOf(userId));

            KnowledgeBaseSegmentEntity segEntity = new KnowledgeBaseSegmentEntity();
            segEntity.setText(seg.text());
            segEntity.setChunkId(metadata.getString(MetadataKeyConstant.CHUNK_ID));
            segEntity.setMetadata(toJson(metadataMap));
            segEntity.setDocumentId(docId);
            segEntity.setDocumentVersion(version.getVersionId());
            segEntity.setChunkOrder(i);
            Integer skip = metadata.getInteger(MetadataKeyConstant.SKIP_EMBEDDING);
            segEntity.setSkipEmbedding(skip != null && skip == 1 ? 1 : 0);
            segEntity.setStatus(SegmentStatus.STORED);
            segmentEntities.add(segEntity);
        }
        segmentService.saveBatch(segmentEntities);

        // 状态升 CHUNKED
        entity.setDocStatus(DocumentStatus.CHUNKED);
        knowledgeBaseRepository.save(entity);
        version.setStatus(DocumentStatus.CHUNKED);
        versionService.update(version);

        // 发事件触发异步向量化
        int segmentCount = segmentEntities.size();
        eventPublisher.publishEvent(new DocumentChunkedEvent(docId, version.getVersionId(), segmentCount));
        log.info("文档切块事件已发布: docId={}, versionId={}, segmentCount={}",
            docId, version.getVersionId(), segmentCount);
        return segmentCount;
    }

    @Override
    public void embedAndStore(KnowledgeBaseVersionEntity version) {
        // 委托给 KnowledgeDocumentService.activateVersion，复用分页扫描嵌入逻辑
        knowledgeDocumentService.activateVersion(version);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int rechunk(Long docId) {
        Long userId = UserContext.requireUserId();
        log.info("重新切块请求: docId={}", docId);
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findByUserIdAndId(userId, docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId));

        Long versionId = entity.getCurrentVersionId();
        if (versionId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库无当前版本，无法重新切块: " + docId);
        }
        KnowledgeBaseVersionEntity version = versionService.getById(versionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在"));

        // 1. 清 ES 旧向量（按 docId+versionId，失败仅告警，不阻断重新切块）
        vectorStoreService.removeByDocIdAndVersion(docId, versionId);
        // 2. 物理删当前版本 segment
        segmentService.physicalDeleteByDocumentVersion(versionId);
        // 3. 版本降回 CONVERTED
        version.setStatus(DocumentStatus.CONVERTED);
        versionService.update(version);
        // 4. 主表降回 CONVERTED
        entity.setDocStatus(DocumentStatus.CONVERTED);
        knowledgeBaseRepository.save(entity);

        // 5. 重新切块发事件触发异步向量化
        return split(docId, "TITLE", null, null);
    }

    private String nextVersion(String current) {
        // 简化：1.0.0 → 1.0.1 → 1.0.2 ...（补丁号自增）
        String[] parts = current.split("\\.");
        if (parts.length != 3) {
            return current + ".1";
        }
        try {
            int patch = Integer.parseInt(parts[2]) + 1;
            return parts[0] + "." + parts[1] + "." + patch;
        } catch (NumberFormatException e) {
            return current + ".1";
        }
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("序列化分段 metadata 失败: {}", e.getMessage());
            return "{}";
        }
    }
}
