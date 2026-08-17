package com.linrun.interview.document.service.impl;
import com.linrun.interview.infra.persistence.EntityQueries;
import com.linrun.interview.infra.persistence.MapperUtils;
import com.linrun.interview.common.annotation.DistributeLock;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.rag.model.EvidenceMetadata;
import com.linrun.interview.document.service.impl.ContentTypeDetectionService;
import com.linrun.interview.document.service.impl.FileHashService;
import com.linrun.interview.document.service.FileStorageService;
import com.linrun.interview.document.service.impl.FileValidationService;
import com.linrun.interview.document.service.DocumentProcessService;
import com.linrun.interview.document.service.KnowledgeDocumentService;
import com.linrun.interview.document.service.KnowledgeDocumentVersionService;
import com.linrun.interview.document.service.KnowledgeSegmentService;
import com.linrun.interview.document.service.VectorStoreService;
import com.linrun.interview.document.service.impl.KnowledgeBaseChunkingService;
import com.linrun.interview.document.service.impl.VectorizationTaskService;
import com.linrun.interview.document.constant.KnowledgeBaseType;
import com.linrun.interview.document.service.ExcelProcessService;
import com.linrun.interview.document.constant.DocumentAccessScope;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.constant.FileType;
import com.linrun.interview.rag.constant.MetadataKeyConstant;
import com.linrun.interview.document.constant.SegmentStatus;
import com.linrun.interview.document.event.DocumentAcceptedEvent;
import com.linrun.interview.document.event.DocumentChunkedEvent;
import com.linrun.interview.document.vo.DocumentSplitParam;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.entity.KnowledgeBaseSegmentEntity;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.document.mapper.TableMetaMapper;
import com.linrun.interview.document.service.FileProcessService;
import com.linrun.interview.document.vo.DocumentParseRequest;
import com.linrun.interview.document.service.FileProcessServiceFactory;
import com.linrun.interview.document.service.impl.FileTypeResolver;
import com.linrun.interview.document.service.impl.ExcelSplitter;
import com.linrun.interview.document.util.DocumentPermissionUtils;
import com.linrun.interview.document.util.VersionUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档处理编排实现。
 *
 * <p>编排 upload（UPLOADED→CONVERTING→CONVERTED/STORED）→ 手动 split（CHUNKED + 事件）→ 异步向量化。
 *
 * <p>与早期实现差异（遵守 ai-interview AGENTS.md）：
 * <ul>
 *   <li>用户隔离用 {@link UserContext#requireUserId()}（非 accessibleBy/uploadUser 字符串）。</li>
 *   <li>解析产物 Markdown 存版本表 convertedDocUrl 或 convertedContent，split 时直接取。</li>
 *   <li>切块策略由 {@link KnowledgeBaseChunkingService} 统一解析，默认使用父子分段；块大小/重叠取自 RAG 配置。</li>
 *   <li>{@code Assert} → {@link BusinessException}；并发控制用 {@code @DistributeLock}（见切面 DistributeLockAspect）。</li>
 *   <li>事务边界：upload/split/rechunk 的 MinIO、解析、切块与 ES 调用均在事务外；仅 segment
 *       落库、状态推进和事件发布通过 {@link TransactionTemplate} 进入同一个短事务。</li>
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
    private final VectorizationTaskService vectorizationTaskService;
    private final TransactionTemplate transactionTemplate;
    private final VectorStoreService vectorStoreService;
    private final ExcelProcessService excelProcessService;
    private final TableMetaMapper tableMetaMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    @DistributeLock(key = "'kb:upload:' + T(com.linrun.interview.auth.security.UserContext).requireUserId() + ':' + #file.originalFilename",
        waitTime = 0, leaseTime = 300, message = "同名文件正在上传，请稍后再试")
    public Long upload(MultipartFile file, String title, String category) {
        return upload(file, title, category, DocumentAccessScope.PRIVATE, null);
    }

    @Override
    @DistributeLock(key = "'kb:upload:' + T(com.linrun.interview.auth.security.UserContext).requireUserId() + ':' + #file.originalFilename",
        waitTime = 0, leaseTime = 300, message = "同名文件正在上传，请稍后再试")
    public Long upload(MultipartFile file, String title, String category,
                       DocumentAccessScope accessScope, LocalDate expireDate) {
        return upload(file, title, category, accessScope, expireDate, KnowledgeBaseType.DOCUMENT_SEARCH);
    }

    @Override
    @DistributeLock(key = "'kb:upload:' + T(com.linrun.interview.auth.security.UserContext).requireUserId() + ':' + #file.originalFilename",
        waitTime = 0, leaseTime = 300, message = "同名文件正在上传，请稍后再试")
    public Long upload(MultipartFile file, String title, String category,
                       DocumentAccessScope accessScope, LocalDate expireDate,
                       KnowledgeBaseType knowledgeBaseType) {
        PersistedUpload persisted = persistUpload(file, title, category, accessScope, expireDate,
            knowledgeBaseType);
        convertPersisted(persisted, readUploadBytes(file));
        log.info("知识库上传完成: docId={}, versionId={}, status={}, kbType={}",
            persisted.entity().getId(), persisted.version().getVersionId(),
            persisted.entity().getDocStatus(), persisted.kbType());
        return persisted.entity().getId();
    }

    @Override
    @DistributeLock(key = "'kb:upload:' + T(com.linrun.interview.auth.security.UserContext).requireUserId() + ':' + #file.originalFilename",
        waitTime = 0, leaseTime = 60, message = "同名文件正在上传，请稍后再试")
    public Long acceptAndEnqueueConvert(MultipartFile file, String title, String category,
                                        DocumentAccessScope accessScope, LocalDate expireDate,
                                        KnowledgeBaseType knowledgeBaseType, boolean splitAfter) {
        PersistedUpload persisted = persistUpload(file, title, category, accessScope, expireDate,
            knowledgeBaseType);
        Long docId = persisted.entity().getId();
        eventPublisher.publishEvent(new DocumentAcceptedEvent(
            docId, persisted.userId(), splitAfter));
        log.info("知识库已接收并排队解析: docId={}, versionId={}, splitAfter={}",
            docId, persisted.version().getVersionId(), splitAfter);
        return docId;
    }

    @Override
    @DistributeLock(key = "'kb:convert:' + #docId", waitTime = 0, leaseTime = -1,
        message = "该文档正在解析，请稍后再试")
    public void processAcceptedDocument(Long docId, boolean splitAfter) {
        if (docId == null) {
            return;
        }
        KnowledgeBaseEntity entity = knowledgeBaseEntityMapper.selectById(docId);
        if (entity == null) {
            log.warn("异步解析跳过，文档不存在: docId={}", docId);
            return;
        }
        Long userId = entity.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文档缺少 userId: " + docId);
        }
        UserContext.setUserId(userId);
        try {
            DocumentStatus status = entity.getDocStatus();
            if (status == DocumentStatus.UPLOADED || status == DocumentStatus.CONVERTING) {
                KnowledgeBaseVersionEntity version = requireCurrentVersion(entity);
                byte[] fileBytes = storageService.downloadFile(entity.getStorageKey());
                convertPersisted(new PersistedUpload(userId, entity, version,
                    resolveKbType(entity.getKnowledgeBaseType())), fileBytes);
                entity = knowledgeBaseEntityMapper.selectById(docId);
            }
            if (splitAfter && entity != null
                && entity.getKnowledgeBaseType() != KnowledgeBaseType.DATA_QUERY
                && entity.getDocStatus() == DocumentStatus.CONVERTED) {
                splitInternal(docId, chunkingService.defaultSplitParam());
            }
        } finally {
            UserContext.clear();
        }
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
        version.setStorageKey(storageKey);
        version.setContentHash(contentHash);
        version.setStatus(DocumentStatus.UPLOADED);
        version.setUploadUser(String.valueOf(userId));
        version.setChangelog(changelog);
        LocalDateTime now = LocalDateTime.now();
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        version = versionService.saveVersion(version);

        // convert 成功后才切主表 currentVersionId（见 finalizeAfterConvert）：
        // 转换可能耗时数分钟（MinerU），期间指针必须始终指向旧的可用版本；
        // convert 失败时指针从未切换，无需回滚
        convertFile(userId, entity, version.getVersionId(), fileBytes, fileName, contentType,
            storageKey, entity.getKnowledgeBaseType() != null
                ? entity.getKnowledgeBaseType() : KnowledgeBaseType.DOCUMENT_SEARCH);

        // 即时失效旧当前版本：若旧版本已向量化，主动清 ES 向量 + segment 降 STORED，不等补偿任务。
        if (latest.getStatus() == DocumentStatus.VECTOR_STORED) {
            try {
                knowledgeDocumentService.deactivateVersion(latest.getVersionId());
            } catch (Exception e) {
                log.warn("新版本上传时失效旧版本失败，已继续上传新版本，旧版本留待补偿任务清理: docId={}, oldVersionId={}, error={}",
                    docId, latest.getVersionId(), e.getMessage(), e);
            }
        }

        if (entity.getKnowledgeBaseType() == KnowledgeBaseType.DATA_QUERY) {
            finalizeAfterDataQuery(entity, version);
        } else {
            finalizeAfterConvert(entity, version);
        }

        log.info("知识库新版本上传完成: docId={}, version={}", docId, newVersion);
        return version.getVersionId();
    }

    @Override
    @DistributeLock(key = "'kb:split:' + #docId", waitTime = 0, leaseTime = -1,
        message = "该知识库正在切块，请稍后再试")
    public int split(Long docId) {
        return split(docId, chunkingService.defaultSplitParam());
    }

    @Override
    @DistributeLock(key = "'kb:split:' + #docId", waitTime = 0, leaseTime = -1,
        message = "该知识库正在切块，请稍后再试")
    public int split(Long docId, DocumentSplitParam splitParam) {
        return splitInternal(docId, splitParam);
    }

    private int splitInternal(Long docId, DocumentSplitParam splitParam) {
        Long userId = UserContext.requireUserId();
        KnowledgeBaseEntity entity = EntityQueries.byUserAndId(knowledgeBaseEntityMapper, userId, docId, KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId));

        if (entity.getKnowledgeBaseType() == KnowledgeBaseType.DATA_QUERY) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "DATA_QUERY 文档不支持切块");
        }
        if (entity.getDocStatus() == DocumentStatus.CHUNKED) {
            long count = segmentService.countByDocumentVersion(entity.getCurrentVersionId());
            log.info("文档已切块，返回现有分段数: docId={}, count={}", docId, count);
            return (int) count;
        }
        KnowledgeBaseVersionEntity version = versionService.findById(entity.getCurrentVersionId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在"));
        if (version.getStatus() == DocumentStatus.CHUNKED) {
            long count = segmentService.countByDocumentVersion(version.getVersionId());
            log.info("版本已切块，返回现有分段数: docId={}, count={}", docId, count);
            return (int) count;
        }
        if (entity.getDocStatus() == DocumentStatus.VECTOR_STORED
            || version.getStatus() == DocumentStatus.VECTOR_STORED) {
            beginOrResumeRechunk(entity, version);
            clearVersionArtifacts(docId, version.getVersionId());
        } else if (entity.getDocStatus() == DocumentStatus.CONVERTED
            && version.getStatus() == DocumentStatus.CONVERTED
            && segmentService.countByDocumentVersion(version.getVersionId()) > 0) {
            // 上次重切块可能在 DB 状态接管后、清理外部向量前失败；保留旧 segment 作为恢复标记。
            clearVersionArtifacts(docId, version.getVersionId());
        }
        if (entity.getDocStatus() != DocumentStatus.CONVERTED
            || version.getStatus() != DocumentStatus.CONVERTED) {
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
            String convertedDocUrl = version.getConvertedDocUrl();
            if (convertedDocUrl == null || convertedDocUrl.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "表格 convertedDocUrl 缺失，无法 ExcelSplitter 切块");
            }
            byte[] fileBytes = storageService.downloadConvertedMarkdown(convertedDocUrl);
            int chunkSize = splitParam.chunkSize() != null
                ? splitParam.chunkSize() : chunkingService.defaultSplitParam().chunkSize();
            try {
                segments = new ExcelSplitter(chunkSize).split(fileBytes);
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                    "Excel 切块失败: " + e.getMessage(), e);
            }
        } else {
            String markdown = resolveConvertedMarkdown(version);
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

            String chunkId = metadata.getString(MetadataKeyConstant.CHUNK_ID);
            String stableChunkId = chunkId != null && !chunkId.isBlank()
                ? chunkId : "order-" + i;
            EvidenceMetadata evidenceMetadata = new EvidenceMetadata(
                userId,
                DataDomain.CANDIDATE,
                String.valueOf(docId),
                String.valueOf(version.getVersionId()),
                "kb:" + docId + ":" + version.getVersionId() + ":" + stableChunkId,
                fileHashService.calculateHash(seg.text().getBytes(StandardCharsets.UTF_8)),
                "KNOWLEDGE_DOCUMENT",
                "chunk:" + stableChunkId);
            putEvidenceMetadata(metadataMap, evidenceMetadata);

            KnowledgeBaseSegmentEntity segEntity = new KnowledgeBaseSegmentEntity();
            segEntity.setText(seg.text());
            segEntity.setChunkId(chunkId);
            // 父子/兄弟关系冗余列（从 metadata 拆出建索引，供检索期 small-to-big 扩展高效查询）
            segEntity.setParentChunkId(metadata.getString(MetadataKeyConstant.PARENT_CHUNK_ID));
            segEntity.setBrotherChunkId(metadata.getString(MetadataKeyConstant.BROTHER_CHUNK_ID));
            Integer brotherIndex = metadata.getInteger(MetadataKeyConstant.BROTHER_CHUNK_INDEX);
            segEntity.setBrotherChunkIndex(brotherIndex);
            segEntity.setMetadata(toJson(metadataMap));
            segEntity.setUserId(evidenceMetadata.ownerUserId());
            segEntity.setDataDomain(evidenceMetadata.dataDomain());
            segEntity.setResourceId(evidenceMetadata.resourceId());
            segEntity.setResourceVersion(evidenceMetadata.resourceVersion());
            segEntity.setEvidenceId(evidenceMetadata.evidenceId());
            segEntity.setContentHash(evidenceMetadata.contentHash());
            segEntity.setSourceType(evidenceMetadata.sourceType());
            segEntity.setSourceLocator(evidenceMetadata.sourceLocator());
            segEntity.setDocumentId(docId);
            segEntity.setDocumentVersion(version.getVersionId());
            segEntity.setChunkOrder(i);
            Integer skip = metadata.getInteger(MetadataKeyConstant.SKIP_EMBEDDING);
            segEntity.setSkipEmbedding(skip != null && skip == 1 ? 1 : 0);
            segEntity.setStatus(SegmentStatus.STORED);
            segmentEntities.add(segEntity);
        }
        int segmentCount = segmentEntities.size();
        // 只把 segment 落库、状态推进和事件发布放进短事务。AFTER_COMMIT 监听器会在该事务
        // 真正提交后触发；MinIO 下载、Excel/Markdown 切块均已在事务外完成。
        transactionTemplate.executeWithoutResult(tx -> {
            segmentService.saveSegments(segmentEntities);
            knowledgeDocumentService.advanceDocumentAndVersionStatus(
                docId, version.getVersionId(), DocumentStatus.CHUNKED);
            eventPublisher.publishEvent(
                new DocumentChunkedEvent(docId, version.getVersionId(), segmentCount));
        });
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
    // 与 split 共用同一把锁，rechunk 直接调用 splitInternal 避免同类调用绕过代理。
    @DistributeLock(key = "'kb:split:' + #docId", waitTime = 0, leaseTime = -1,
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
        KnowledgeBaseVersionEntity version = versionService.findById(versionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在"));

        // 先用 DB 状态接管任务，再清理外部向量。失败时保留 CONVERTED + 旧 segment，重试可继续收敛。
        beginOrResumeRechunk(entity, version);
        clearVersionArtifacts(docId, versionId);

        // 重新切块发事件触发异步向量化
        return splitInternal(docId, chunkingService.defaultSplitParam());
    }

    private void beginOrResumeRechunk(
        KnowledgeBaseEntity entity, KnowledgeBaseVersionEntity version) {
        if (entity.getDocStatus() == DocumentStatus.CONVERTED
            && version.getStatus() == DocumentStatus.CONVERTED) {
            return;
        }
        if (entity.getDocStatus() != DocumentStatus.VECTOR_STORED
            || version.getStatus() != DocumentStatus.VECTOR_STORED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "文档状态已变化，无法重新切块: docId=" + entity.getId());
        }
        transactionTemplate.executeWithoutResult(tx -> {
            if (knowledgeBaseEntityMapper.beginRechunk(entity.getId(), version.getVersionId()) != 1
                || !versionService.beginRechunk(version.getVersionId(), entity.getId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "文档状态已变化，无法重新切块: docId=" + entity.getId());
            }
        });
        entity.setDocStatus(DocumentStatus.CONVERTED);
        version.setStatus(DocumentStatus.CONVERTED);
    }

    private void clearVersionArtifacts(Long docId, Long versionId) {
        vectorStoreService.removeByDocIdAndVersion(docId, versionId);
        transactionTemplate.executeWithoutResult(tx -> {
            segmentService.physicalDeleteByDocumentVersion(versionId);
            vectorizationTaskService.reset(versionId);
        });
    }

    @Override
    @DistributeLock(key = "'kb:switch:' + #docId", waitTime = 0, leaseTime = 180,
        message = "该知识库正在切换版本，请稍后再试")
    public void switchVersion(Long docId, Long versionId) {
        Long userId = UserContext.requireUserId();
        log.info("切换当前版本: docId={}, targetVersionId={}", docId, versionId);
        KnowledgeBaseEntity doc = EntityQueries.byUserAndId(knowledgeBaseEntityMapper, userId, docId, KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId));
        KnowledgeBaseVersionEntity target = versionService.findById(versionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在: " + versionId));
        if (!target.getDocId().equals(docId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "版本不属于该知识库: docId=" + docId + ", versionId=" + versionId);
        }


        if (doc.getKnowledgeBaseType() == KnowledgeBaseType.DATA_QUERY) {
            if (versionId.equals(doc.getCurrentVersionId())) {
                log.info("DATA_QUERY 目标版本已是当前版本，跳过: docId={}, versionId={}", docId, versionId);
                return;
            }
            KnowledgeBaseVersionEntity latest = versionService.findLatestByDocId(docId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库无版本记录: " + docId));
            if (VersionUtil.compareVersions(target.getVersion(), latest.getVersion()) < 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "DATA_QUERY 类型文档不支持切换到旧版本");
            }
            doc.setCurrentVersionId(versionId);
            MapperUtils.save(knowledgeBaseEntityMapper, doc);
            log.info("DATA_QUERY 版本切换完成: docId={}, versionId={}", docId, versionId);
            return;
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


    @Override
    public Page<Map<String, Object>> previewData(Long docId, int current, int size) {
        Long userId = UserContext.requireUserId();
        KnowledgeBaseEntity doc = EntityQueries.byUserAndId(
                knowledgeBaseEntityMapper, userId, docId,
                KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId));
        if (doc.getKnowledgeBaseType() != KnowledgeBaseType.DATA_QUERY) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持数据查询类型文档");
        }
        String physicalTableName = doc.getTableName();
        if (physicalTableName == null || physicalTableName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档未配置数据表名称");
        }
        if (tableMetaMapper.checkTableExists(physicalTableName) <= 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "数据表不存在");
        }
        int pageNo = Math.max(current, 1);
        int pageSize = Math.max(size, 1);
        long total = ((Number) tableMetaMapper.executeQuery(
            "SELECT COUNT(*) AS cnt FROM `" + physicalTableName + "`").getFirst().get("cnt")).longValue();
        Page<Map<String, Object>> page = new Page<>(pageNo, pageSize, total);
        if (total > 0) {
            long offset = (long) (pageNo - 1) * pageSize;
            String querySql = "SELECT * FROM `" + physicalTableName
                + "` ORDER BY id ASC LIMIT " + pageSize + " OFFSET " + offset;
            page.setRecords(tableMetaMapper.executeQuery(querySql));
        }
        return page;
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

    private void putEvidenceMetadata(
        Map<String, String> metadata,
        EvidenceMetadata evidenceMetadata
    ) {
        metadata.put(MetadataKeyConstant.OWNER_USER_ID,
            String.valueOf(evidenceMetadata.ownerUserId()));
        metadata.put(MetadataKeyConstant.DATA_DOMAIN, evidenceMetadata.dataDomain().name());
        metadata.put(MetadataKeyConstant.RESOURCE_ID, evidenceMetadata.resourceId());
        metadata.put(MetadataKeyConstant.RESOURCE_VERSION, evidenceMetadata.resourceVersion());
        metadata.put(MetadataKeyConstant.EVIDENCE_ID, evidenceMetadata.evidenceId());
        metadata.put(MetadataKeyConstant.CONTENT_HASH, evidenceMetadata.contentHash());
        metadata.put(MetadataKeyConstant.SOURCE_TYPE, evidenceMetadata.sourceType());
        metadata.put(MetadataKeyConstant.SOURCE_LOCATOR, evidenceMetadata.sourceLocator());
    }

    private PersistedUpload persistUpload(MultipartFile file, String title, String category,
                                          DocumentAccessScope accessScope, LocalDate expireDate,
                                          KnowledgeBaseType knowledgeBaseType) {
        KnowledgeBaseType kbType = resolveKbType(knowledgeBaseType);
        if (kbType == KnowledgeBaseType.DATA_QUERY) {
            String probeName = file.getOriginalFilename();
            FileType fileType = fileTypeResolver.resolve(probeName,
                contentTypeDetectionService.detectContentType(file));
            if (fileType != FileType.EXCEL && fileType != FileType.CSV) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "DATA_QUERY 仅支持 Excel/CSV 文件");
            }
        }
        Long userId = UserContext.requireUserId();
        String fileName = file.getOriginalFilename();

        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");
        String contentType = contentTypeDetectionService.detectContentType(file);
        if (!fileValidationService.isKnowledgeBaseMimeType(contentType)
            && !fileValidationService.isMarkdownExtension(fileName)
            && !fileValidationService.isSpreadsheetExtension(fileName)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "不支持的文件类型，仅支持 PDF、DOCX、DOC、TXT、MD、CSV、Excel 等");
        }
        String contentHash = fileHashService.calculateHash(file);
        if (versionService.findByContentHash(contentHash, userId).isPresent()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档内容已存在，请勿重复上传");
        }

        String storageKey = storageService.uploadKnowledgeBase(file);
        String docUrl = storageService.getFileUrl(storageKey);

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
        entity.setAccessibleBy(accessScope != null ? accessScope.name() : DocumentAccessScope.PRIVATE.name());
        entity.setExpireDate(expireDate);
        entity.setDocStatus(DocumentStatus.UPLOADED);
        entity.setKnowledgeBaseType(kbType);
        if (kbType == KnowledgeBaseType.DATA_QUERY) {
            entity.setTableName(excelProcessService.generatePhysicalTableName(userId, fileName));
        }
        entity = MapperUtils.save(knowledgeBaseEntityMapper, entity);

        KnowledgeBaseVersionEntity version = new KnowledgeBaseVersionEntity();
        version.setDocId(entity.getId());
        version.setVersion(INITIAL_VERSION);
        version.setDocUrl(docUrl);
        version.setStorageKey(storageKey);
        version.setContentHash(contentHash);
        version.setStatus(DocumentStatus.UPLOADED);
        version.setUploadUser(String.valueOf(userId));
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        version = versionService.saveVersion(version);

        entity.setCurrentVersionId(version.getVersionId());
        MapperUtils.save(knowledgeBaseEntityMapper, entity);
        return new PersistedUpload(userId, entity, version, kbType);
    }

    private void convertPersisted(PersistedUpload persisted, byte[] fileBytes) {
        KnowledgeBaseEntity entity = persisted.entity();
        KnowledgeBaseVersionEntity version = persisted.version();
        convertFile(persisted.userId(), entity, version.getVersionId(), fileBytes,
            entity.getOriginalFilename(), entity.getContentType(), entity.getStorageKey(),
            persisted.kbType());
        if (persisted.kbType() == KnowledgeBaseType.DATA_QUERY) {
            finalizeAfterDataQuery(entity, version);
        } else {
            finalizeAfterConvert(entity, version);
        }
    }

    private byte[] readUploadBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                "读取上传文件失败: " + e.getMessage(), e);
        }
    }

    private KnowledgeBaseVersionEntity requireCurrentVersion(KnowledgeBaseEntity entity) {
        if (entity.getCurrentVersionId() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库无当前版本: " + entity.getId());
        }
        return versionService.findById(entity.getCurrentVersionId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在"));
    }

    private KnowledgeBaseType resolveKbType(KnowledgeBaseType knowledgeBaseType) {
        return knowledgeBaseType != null ? knowledgeBaseType : KnowledgeBaseType.DOCUMENT_SEARCH;
    }

    private record PersistedUpload(
        Long userId,
        KnowledgeBaseEntity entity,
        KnowledgeBaseVersionEntity version,
        KnowledgeBaseType kbType
    ) {}

    /**
     * 同步转换阶段（对齐业界实践 processFile）：UPLOADED → CONVERTING → CONVERTED/STORED。
     */
    private String convertFile(Long userId, KnowledgeBaseEntity entity, Long versionId,
                               byte[] fileBytes, String fileName, String contentType,
                               String storageKey, KnowledgeBaseType kbType) {
        Long docId = entity.getId();
        knowledgeDocumentService.advanceDocumentAndVersionStatus(docId, versionId, DocumentStatus.CONVERTING);
        try {
            FileType fileType = fileTypeResolver.resolve(fileName, contentType);
            if (isDocumentSearchSpreadsheet(fileType, kbType)) {
                return finalizeSpreadsheetPassthrough(docId, versionId);
            }
            FileProcessService processor = fileProcessServiceFactory.get(fileType, kbType);
            String converted = processor.processDocument(new DocumentParseRequest(
                userId, docId, versionId, fileBytes, fileName, contentType, storageKey, kbType));

            if (kbType == KnowledgeBaseType.DATA_QUERY) {
                knowledgeDocumentService.advanceDocumentAndVersionStatus(
                    docId, versionId, DocumentStatus.STORED);
                return null;
            }
            if (converted == null || converted.isBlank()) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文件解析结果为空");
            }

            KnowledgeBaseVersionEntity version = versionService.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在"));
            if (converted.startsWith("http://") || converted.startsWith("https://")) {
                version.setConvertedDocUrl(converted);
            } else {
                version.setConvertedDocUrl(
                    storageService.uploadConvertedMarkdown(docId, versionId, converted));
            }
            versionService.updateVersion(version);
            knowledgeDocumentService.advanceDocumentAndVersionStatus(
                docId, versionId, DocumentStatus.CONVERTED);
            return converted;
        } catch (BusinessException e) {
            rollbackToUploaded(docId, versionId);
            throw e;
        } catch (Exception e) {
            rollbackToUploaded(docId, versionId);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                "文件解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * DOCUMENT_SEARCH 的 Excel/CSV 不转 Markdown，
     * convertedDocUrl 直接指向原文件，split 时从 convertedDocUrl 下载后 ExcelSplitter 切块。
     */
    private boolean isDocumentSearchSpreadsheet(FileType fileType, KnowledgeBaseType kbType) {
        return kbType == KnowledgeBaseType.DOCUMENT_SEARCH
            && (fileType == FileType.EXCEL || fileType == FileType.CSV);
    }

    private String finalizeSpreadsheetPassthrough(Long docId, Long versionId) {
        KnowledgeBaseVersionEntity version = versionService.findById(versionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在"));
        String docUrl = version.getDocUrl();
        if (docUrl == null || docUrl.isBlank()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "原文件地址缺失");
        }
        version.setConvertedDocUrl(docUrl);
        versionService.updateVersion(version);
        knowledgeDocumentService.advanceDocumentAndVersionStatus(
            docId, versionId, DocumentStatus.CONVERTED);
        log.info("表格文档检索跳过 Markdown 转换，convertedDocUrl 指向原文件: docId={}, versionId={}",
            docId, versionId);
        return null;
    }

    private String resolveConvertedMarkdown(KnowledgeBaseVersionEntity version) {
        if (version.getConvertedDocUrl() != null && !version.getConvertedDocUrl().isBlank()) {
            return new String(
                storageService.downloadConvertedMarkdown(version.getConvertedDocUrl()),
                StandardCharsets.UTF_8);
        }
        return version.getConvertedContent();
    }

    private void finalizeAfterDataQuery(KnowledgeBaseEntity entity, KnowledgeBaseVersionEntity version) {
        entity.setCurrentVersionId(version.getVersionId());
        entity.setDocStatus(DocumentStatus.STORED);
        MapperUtils.save(knowledgeBaseEntityMapper, entity);
    }

    private void finalizeAfterConvert(KnowledgeBaseEntity entity, KnowledgeBaseVersionEntity version) {
        entity.setCurrentVersionId(version.getVersionId());
        entity.setDocStatus(DocumentStatus.CONVERTED);
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
        versionService.findById(versionId).ifPresent(v -> {
            v.setStatus(DocumentStatus.UPLOADED);
            versionService.updateVersion(v);
        });
        log.warn("文档转换失败，状态已回滚为 UPLOADED: docId={}, versionId={}", docId, versionId);
    }

}
