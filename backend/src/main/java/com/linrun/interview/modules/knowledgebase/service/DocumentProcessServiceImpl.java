package com.linrun.interview.modules.knowledgebase.service;


import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.common.annotation.DistributeLock;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.infrastructure.file.ContentTypeDetectionService;
import com.linrun.interview.infrastructure.file.FileHashService;
import com.linrun.interview.infrastructure.file.FileStorageService;
import com.linrun.interview.infrastructure.file.FileValidationService;
import com.linrun.interview.modules.knowledgebase.constant.DocumentAccessScope;
import com.linrun.interview.modules.knowledgebase.constant.DocumentStatus;
import com.linrun.interview.modules.knowledgebase.constant.FileType;
import com.linrun.interview.modules.knowledgebase.constant.KnowledgeBaseType;
import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;
import com.linrun.interview.modules.knowledgebase.constant.SegmentStatus;
import com.linrun.interview.modules.knowledgebase.event.DocumentChunkedEvent;
import com.linrun.interview.modules.knowledgebase.model.DocumentSplitParam;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.modules.knowledgebase.service.parse.FileProcessService;
import com.linrun.interview.modules.knowledgebase.service.parse.FileProcessServiceFactory;
import com.linrun.interview.modules.knowledgebase.service.parse.FileTypeResolver;
import com.linrun.interview.modules.knowledgebase.service.parse.SpreadsheetProcessService;
import com.linrun.interview.modules.knowledgebase.service.splitter.ExcelSplitter;
import com.linrun.interview.modules.knowledgebase.util.DocumentPermissionUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档处理编排实现（对齐业界实践 DocumentProcessServiceImpl）。
 *
 * <p>编排 upload（UPLOADED→CONVERTING→CONVERTED）→ 手动 split（CHUNKED + 事件）→ 异步向量化。
 *
 * <p>与早期实现差异（遵守 ai-interview AGENTS.md）：
 * <ul>
 *   <li>用户隔离用 {@link UserContext#requireUserId()}（非 accessibleBy/uploadUser 字符串）。</li>
 *   <li>解析产物 Markdown 直接存版本表 {@code convertedContent}（Lob），split 时直接取，省存储往返。</li>
 *   <li>切块固定用 {@link MarkdownHeaderBrotherTextSplitter}，块大小/重叠取自 {@link KnowledgeBaseQueryProperties}。</li>
 *   <li>{@code Assert} → {@link BusinessException}；并发控制用 {@code @DistributeLock}（见切面 DistributeLockAspect）。</li>
 *   <li>事务边界：upload/split 含存储/解析外部调用，不加 {@code @Transactional}；DB 写操作走各 Service 自身事务。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessServiceImpl implements DocumentProcessService {

    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private static final String INITIAL_VERSION = "1.0.0";

    private final FileProcessServiceFactory fileProcessServiceFactory;
    private final FileTypeResolver fileTypeResolver;
    private final FileStorageService storageService;
    private final FileHashService fileHashService;
    private final FileValidationService fileValidationService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;
    private final KnowledgeDocumentVersionService versionService;
    private final KnowledgeSegmentService segmentService;
    private final KnowledgeBaseChunkingService chunkingService;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeBaseDataTableService dataTableService;
    private final VectorStoreService vectorStoreService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    @DistributeLock(key = "'kb:upload:' + T(com.linrun.interview.common.security.UserContext).requireUserId() + ':' + #file.originalFilename",
        waitTime = 0, leaseTime = 300, message = "同名文件正在上传，请稍后再试")
    public Long upload(MultipartFile file, String title, String category) {
        return upload(file, title, category, KnowledgeBaseType.DOCUMENT_SEARCH);
    }

    @Override
    @DistributeLock(key = "'kb:upload:' + T(com.linrun.interview.common.security.UserContext).requireUserId() + ':' + #file.originalFilename",
        waitTime = 0, leaseTime = 300, message = "同名文件正在上传，请稍后再试")
    public Long upload(MultipartFile file, String title, String category, KnowledgeBaseType knowledgeBaseType) {
        return upload(file, title, category, knowledgeBaseType, DocumentAccessScope.PRIVATE, null);
    }

    @Override
    @DistributeLock(key = "'kb:upload:' + T(com.linrun.interview.common.security.UserContext).requireUserId() + ':' + #file.originalFilename",
        waitTime = 0, leaseTime = 300, message = "同名文件正在上传，请稍后再试")
    public Long upload(MultipartFile file, String title, String category, KnowledgeBaseType knowledgeBaseType,
                       DocumentAccessScope accessScope, LocalDate expireDate) {
        KnowledgeBaseType type = knowledgeBaseType != null ? knowledgeBaseType : KnowledgeBaseType.DOCUMENT_SEARCH;
        Long userId = UserContext.requireUserId();
        String fileName = file.getOriginalFilename();

        // 1. 校验
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");
        String contentType = contentTypeDetectionService.detectContentType(file);
        if (!fileValidationService.isKnowledgeBaseMimeType(contentType)
            && !fileValidationService.isMarkdownExtension(fileName)
            && !fileValidationService.isSpreadsheetExtension(fileName)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "不支持的文件类型，仅支持 PDF、DOCX、DOC、TXT、MD、CSV、Excel 等");
        }
        if (type == KnowledgeBaseType.DATA_QUERY
            && !fileValidationService.isSpreadsheetExtension(fileName)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "DATA_QUERY 类型仅支持 CSV / Excel 表格文件");
        }

        // 2. 内容哈希去重（按用户隔离的跨版本去重；跨用户不互相阻断，也不泄漏他人文档存在性）
        String contentHash = fileHashService.calculateHash(file);
        if (versionService.findByContentHash(contentHash, userId).isPresent()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档内容已存在，请勿重复上传");
        }

        // 3. 存原始文件到 RustFS
        String storageKey = storageService.uploadKnowledgeBase(file);
        String docUrl = storageService.getFileUrl(storageKey);

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                "读取上传文件失败: " + e.getMessage(), e);
        }

        // 4. 落库主表 + 版本（UPLOADED），再同步转换（UPLOADED → CONVERTING → CONVERTED）
        LocalDateTime now = LocalDateTime.now();
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
        entity.setUploadedAt(now);
        entity.setKnowledgeBaseType(type.name());
        entity.setAccessibleBy(accessScope != null ? accessScope.name() : DocumentAccessScope.PRIVATE.name());
        entity.setExpireDate(expireDate);
        entity.setDocStatus(DocumentStatus.UPLOADED);
        entity = MapperUtils.save(knowledgeBaseEntityMapper, entity);

        KnowledgeBaseVersionEntity version = new KnowledgeBaseVersionEntity();
        version.setDocId(entity.getId());
        version.setVersion(INITIAL_VERSION);
        version.setDocUrl(docUrl);
        version.setContentHash(contentHash);
        version.setStatus(DocumentStatus.UPLOADED);
        version.setUploadUser(String.valueOf(userId));
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        version = versionService.save(version);

        entity.setCurrentVersionId(version.getVersionId());
        MapperUtils.save(knowledgeBaseEntityMapper, entity);

        ConvertResult convertResult = convertFile(
            entity.getId(), version.getVersionId(), fileBytes, fileName, contentType, type);
        finalizeAfterConvert(entity, version, type, convertResult.spreadsheet());

        log.info("知识库上传完成: docId={}, versionId={}, type={}, status={}, markdownChars={}",
            entity.getId(), version.getVersionId(), type, entity.getDocStatus(),
            convertResult.markdown().length());
        return entity.getId();
    }

    @Override
    @DistributeLock(key = "'kb:newversion:' + #docId", waitTime = 0, leaseTime = 300,
        message = "该知识库正在上传新版本，请稍后再试")
    public Long uploadNewVersion(Long docId, MultipartFile file, String changelog) {
        Long userId = UserContext.requireUserId();
        KnowledgeBaseEntity entity = EntityQueries.byUserAndId(knowledgeBaseEntityMapper, userId, docId, KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId));

        String fileName = file.getOriginalFilename();
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");
        String contentType = contentTypeDetectionService.detectContentType(file);
        if (!fileValidationService.isKnowledgeBaseMimeType(contentType)
            && !fileValidationService.isMarkdownExtension(fileName)
            && !fileValidationService.isSpreadsheetExtension(fileName)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "不支持的文件类型，仅支持 PDF、DOCX、DOC、TXT、MD、CSV、Excel 等");
        }

        // 版本号自增：取当前最新版本号 +1
        KnowledgeBaseVersionEntity latest = versionService.findLatestByDocId(docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库无版本记录: " + docId));
        String newVersion = nextVersion(latest.getVersion());

        // 内容哈希去重（按用户隔离，与主表 (user_id, file_hash) 唯一键一致）
        String contentHash = fileHashService.calculateHash(file);
        if (versionService.findByContentHash(contentHash, userId).isPresent()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档内容已存在，请勿重复上传");
        }

        String storageKey = storageService.uploadKnowledgeBase(file);
        String docUrl = storageService.getFileUrl(storageKey);

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                "读取上传文件失败: " + e.getMessage(), e);
        }

        KnowledgeBaseVersionEntity version = new KnowledgeBaseVersionEntity();
        version.setDocId(docId);
        version.setVersion(newVersion);
        version.setDocUrl(docUrl);
        version.setContentHash(contentHash);
        version.setStatus(DocumentStatus.UPLOADED);
        version.setUploadUser(String.valueOf(userId));
        version.setChangelog(changelog);
        LocalDateTime now = LocalDateTime.now();
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        version = versionService.save(version);

        // convert 成功后才切主表 currentVersionId（见 finalizeAfterConvert）：
        // 转换可能耗时数分钟（MinerU），期间指针必须始终指向旧的可用版本；
        // convert 失败时指针从未切换，无需回滚
        KnowledgeBaseType kbType = resolveKnowledgeBaseType(entity);
        ConvertResult convertResult =
            convertFile(docId, version.getVersionId(), fileBytes, fileName, contentType, kbType);

        // 即时失效旧当前版本：若旧版本已向量化，主动清 ES 向量 + segment 降 STORED，不等补偿任务。
        if (latest.getStatus() == DocumentStatus.VECTOR_STORED) {
            try {
                knowledgeDocumentService.deactivateVersion(latest.getVersionId());
            } catch (Exception e) {
                log.warn("新版本上传时失效旧版本失败，已继续上传新版本，旧版本留待补偿任务清理: docId={}, oldVersionId={}, error={}",
                    docId, latest.getVersionId(), e.getMessage(), e);
            }
        }

        finalizeAfterConvert(entity, version, kbType, convertResult.spreadsheet());

        log.info("知识库新版本上传完成: docId={}, version={}, 旧版本即时失效已尝试",
            docId, newVersion);
        return version.getVersionId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DistributeLock(key = "'kb:split:' + #docId", waitTime = 0, leaseTime = 120,
        message = "该知识库正在切块，请稍后再试")
    public int split(Long docId) {
        return split(docId, chunkingService.defaultSplitParam());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DistributeLock(key = "'kb:split:' + #docId", waitTime = 0, leaseTime = 120,
        message = "该知识库正在切块，请稍后再试")
    public int split(Long docId, DocumentSplitParam splitParam) {
        return splitInternal(docId, splitParam);
    }

    private int splitInternal(Long docId, DocumentSplitParam splitParam) {
        Long userId = UserContext.requireUserId();
        KnowledgeBaseEntity entity = EntityQueries.byUserAndId(knowledgeBaseEntityMapper, userId, docId, KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId));

        if (KnowledgeBaseType.DATA_QUERY.name().equals(entity.getKnowledgeBaseType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "DATA_QUERY 类型知识库不支持向量化切块");
        }

        if (entity.getDocStatus() == DocumentStatus.CHUNKED) {
            long count = segmentService.countByDocumentVersion(entity.getCurrentVersionId());
            log.info("文档已切块，返回现有分段数: docId={}, count={}", docId, count);
            return (int) count;
        }
        KnowledgeBaseVersionEntity version = versionService.getById(entity.getCurrentVersionId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在"));
        if (version.getStatus() == DocumentStatus.CHUNKED) {
            long count = segmentService.countByDocumentVersion(version.getVersionId());
            log.info("版本已切块，返回现有分段数: docId={}, count={}", docId, count);
            return (int) count;
        }
        if (entity.getDocStatus() == DocumentStatus.VECTOR_STORED) {
            vectorStoreService.removeByDocIdAndVersion(docId, version.getVersionId());
            segmentService.physicalDeleteByDocumentVersion(version.getVersionId());
            version.setStatus(DocumentStatus.CONVERTED);
            versionService.update(version);
            entity.setDocStatus(DocumentStatus.CONVERTED);
            MapperUtils.save(knowledgeBaseEntityMapper, entity);
        }
        if (entity.getDocStatus() != DocumentStatus.CONVERTED
            && version.getStatus() != DocumentStatus.CONVERTED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "文档状态不为 CONVERTED，无法切块，当前状态: " + entity.getDocStatus());
        }

        if (DocumentPermissionUtils.isExpired(entity.getExpireDate())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档已过期，无法切块");
        }

        splitParam = chunkingService.resolveSplitParam(splitParam);
        log.info("切块请求: docId={}, splitType={}", docId, splitParam.splitType());

        List<TextSegment> segments;
        if (fileValidationService.isSpreadsheetExtension(entity.getOriginalFilename())) {
            if (entity.getStorageKey() == null || entity.getStorageKey().isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "表格原文件缺失，无法 ExcelSplitter 切块");
            }
            byte[] fileBytes = storageService.downloadFile(entity.getStorageKey());
            int chunkSize = splitParam.chunkSize() != null
                ? splitParam.chunkSize() : chunkingService.defaultSplitParam().chunkSize();
            try {
                segments = new ExcelSplitter(chunkSize).split(fileBytes);
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                    "Excel 切块失败: " + e.getMessage(), e);
            }
        } else {
            String markdown = version.getConvertedContent();
            if (markdown == null || markdown.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "转换后内容为空，请重新上传");
            }
            segments = chunkingService.split(markdown, splitParam);
        }
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
            metadataMap.put(MetadataKeyConstant.ACCESSIBLE_BY, DocumentPermissionUtils.metadataAccessibleBy(entity));
            DocumentPermissionUtils.putExpireDate(metadataMap, entity.getExpireDate());

            KnowledgeBaseSegmentEntity segEntity = new KnowledgeBaseSegmentEntity();
            segEntity.setText(seg.text());
            segEntity.setChunkId(metadata.getString(MetadataKeyConstant.CHUNK_ID));
            // 父子/兄弟关系冗余列（从 metadata 拆出建索引，供检索期 small-to-big 扩展高效查询）
            segEntity.setParentChunkId(metadata.getString(MetadataKeyConstant.PARENT_CHUNK_ID));
            segEntity.setBrotherChunkId(metadata.getString(MetadataKeyConstant.BROTHER_CHUNK_ID));
            Integer brotherIndex = metadata.getInteger(MetadataKeyConstant.BROTHER_CHUNK_INDEX);
            segEntity.setBrotherChunkIndex(brotherIndex);
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

        // 状态升 CHUNKED（单调推进，对齐业界实践 split）
        knowledgeDocumentService.advanceDocumentAndVersionStatus(
            docId, version.getVersionId(), DocumentStatus.CHUNKED);

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
    // 与 split 共用同一把锁，rechunk 直接调用 splitInternal 避免同类调用绕过代理。
    @DistributeLock(key = "'kb:split:' + #docId", waitTime = 0, leaseTime = 180,
        message = "该知识库正在切块或重新向量化，请稍后再试")
    public int rechunk(Long docId) {
        Long userId = UserContext.requireUserId();
        log.info("重新切块请求: docId={}", docId);
        KnowledgeBaseEntity entity = EntityQueries.byUserAndId(knowledgeBaseEntityMapper, userId, docId, KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId));

        Long versionId = entity.getCurrentVersionId();
        if (versionId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库无当前版本，无法重新切块: " + docId);
        }
        KnowledgeBaseVersionEntity version = versionService.getById(versionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在"));

        // 1. 清 ES 旧向量（按 docId+versionId，失败抛异常回滚，避免残留孤儿向量）
        vectorStoreService.removeByDocIdAndVersion(docId, versionId);
        // 2. 物理删当前版本 segment
        segmentService.physicalDeleteByDocumentVersion(versionId);
        // 3. 版本降回 CONVERTED
        version.setStatus(DocumentStatus.CONVERTED);
        versionService.update(version);
        // 4. 主表降回 CONVERTED
        entity.setDocStatus(DocumentStatus.CONVERTED);
        MapperUtils.save(knowledgeBaseEntityMapper, entity);

        // 5. 重新切块发事件触发异步向量化
        return splitInternal(docId, chunkingService.defaultSplitParam());
    }

    @Override
    @DistributeLock(key = "'kb:switch:' + #docId", waitTime = 0, leaseTime = 180,
        message = "该知识库正在切换版本，请稍后再试")
    public void switchVersion(Long docId, Long versionId) {
        Long userId = UserContext.requireUserId();
        log.info("切换当前版本: docId={}, targetVersionId={}", docId, versionId);
        KnowledgeBaseEntity doc = EntityQueries.byUserAndId(knowledgeBaseEntityMapper, userId, docId, KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId));
        KnowledgeBaseVersionEntity target = versionService.getById(versionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在: " + versionId));
        if (!target.getDocId().equals(docId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "版本不属于该知识库: docId=" + docId + ", versionId=" + versionId);
        }

        Long currentVersionId = doc.getCurrentVersionId();
        if (versionId.equals(currentVersionId) && doc.getDocStatus() == DocumentStatus.VECTOR_STORED) {
            log.info("目标版本已是当前激活版本，跳过: docId={}, versionId={}", docId, versionId);
            return;
        }

        // 目标版本未向量化：先激活向量化（activateVersion 内部会自动失效旧当前版本的 ES 向量）
        if (target.getStatus() != DocumentStatus.VECTOR_STORED) {
            knowledgeDocumentService.activateVersion(target);
            // activateVersion 已同步主表 currentVersionId + docStatus，切换完成
            log.info("切换完成（目标版本已激活向量化）: docId={}, versionId={}", docId, versionId);
            return;
        }

        // 目标版本已向量化：热切换——旧版本 DB 降级 + 主表指针更新在一个事务内完成，
        // 旧版本 ES 向量清理移到事务提交后（事务内禁止外部 API）。
        // 目标版本 ES 向量本就存在（按 metadata version 过滤），无需重建。
        knowledgeDocumentService.switchActiveVersion(docId, versionId);
        log.info("切换完成（热切换，无重建）: docId={}, versionId={}", docId, versionId);
    }

    private KnowledgeBaseType resolveKnowledgeBaseType(KnowledgeBaseEntity entity) {
        if (entity.getKnowledgeBaseType() == null) {
            return KnowledgeBaseType.DOCUMENT_SEARCH;
        }
        try {
            return KnowledgeBaseType.valueOf(entity.getKnowledgeBaseType());
        } catch (IllegalArgumentException e) {
            return KnowledgeBaseType.DOCUMENT_SEARCH;
        }
    }

    private String nextVersion(String current) {
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

    /**
     * 同步转换阶段（对齐业界实践 processFile）：UPLOADED → CONVERTING → CONVERTED/STORED。
     */
    private ConvertResult convertFile(Long docId, Long versionId, byte[] fileBytes, String fileName,
                                      String contentType, KnowledgeBaseType type) {
        knowledgeDocumentService.advanceDocumentAndVersionStatus(docId, versionId, DocumentStatus.CONVERTING);
        try {
            FileType fileType = fileTypeResolver.resolve(fileName, contentType);
            FileProcessService processor = fileProcessServiceFactory.get(fileType);
            SpreadsheetProcessService.ParsedSpreadsheet spreadsheet = null;
            String markdown;
            if (processor instanceof SpreadsheetProcessService spreadsheetProcessService) {
                spreadsheet = spreadsheetProcessService.parse(fileBytes, fileName);
                markdown = spreadsheet.markdown();
            } else {
                markdown = processor.processDocument(fileBytes, fileName);
            }
            if (markdown == null || markdown.isBlank()) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文件解析结果为空");
            }

            KnowledgeBaseVersionEntity version = versionService.getById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在"));
            version.setConvertedContent(markdown);
            versionService.update(version);

            DocumentStatus targetStatus = type == KnowledgeBaseType.DATA_QUERY
                ? DocumentStatus.STORED : DocumentStatus.CONVERTED;
            knowledgeDocumentService.advanceDocumentAndVersionStatus(docId, versionId, targetStatus);
            return new ConvertResult(markdown, spreadsheet);
        } catch (BusinessException e) {
            rollbackToUploaded(docId, versionId);
            throw e;
        } catch (Exception e) {
            rollbackToUploaded(docId, versionId);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                "文件解析失败: " + e.getMessage(), e);
        }
    }

    private void finalizeAfterConvert(KnowledgeBaseEntity entity, KnowledgeBaseVersionEntity version,
                                      KnowledgeBaseType type,
                                      SpreadsheetProcessService.ParsedSpreadsheet spreadsheet) {
        Long userId = UserContext.requireUserId();
        if (spreadsheet != null && type == KnowledgeBaseType.DATA_QUERY) {
            var dataTable = dataTableService.createForSpreadsheet(entity, spreadsheet);
            if (dataTable != null) {
                entity.setDataTableName(dataTable.getPhysicalTableName());
                entity.setDataSchemaJson(dataTable.getColumnsJson());
                entity.setDataRowCount(dataTable.getRowCount());
            }
        } else if (spreadsheet != null) {
            entity.setDataTableName(null);
            entity.setDataSchemaJson(null);
            entity.setDataRowCount(null);
            dataTableService.deleteByDoc(userId, entity.getId());
        }
        entity.setCurrentVersionId(version.getVersionId());
        entity.setDocStatus(type == KnowledgeBaseType.DATA_QUERY
            ? DocumentStatus.STORED : DocumentStatus.CONVERTED);
        MapperUtils.save(knowledgeBaseEntityMapper, entity);
    }

    private void rollbackToUploaded(Long docId, Long versionId) {
        // 主表状态仅在指针确实指向失败版本时回滚；
        // 新版本上传失败时指针仍指向旧激活版本，不能篡改其状态
        KnowledgeBaseEntity entity = knowledgeBaseEntityMapper.selectById(docId);
        if (entity != null && versionId.equals(entity.getCurrentVersionId())) {
            entity.setDocStatus(DocumentStatus.UPLOADED);
            MapperUtils.save(knowledgeBaseEntityMapper, entity);
        }
        versionService.getById(versionId).ifPresent(v -> {
            v.setStatus(DocumentStatus.UPLOADED);
            versionService.update(v);
        });
        log.warn("文档转换失败，状态已回滚为 UPLOADED: docId={}, versionId={}", docId, versionId);
    }

    private record ConvertResult(String markdown, SpreadsheetProcessService.ParsedSpreadsheet spreadsheet) {}
}
