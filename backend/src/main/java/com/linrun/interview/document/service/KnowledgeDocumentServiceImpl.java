package com.linrun.interview.document.service;
import com.linrun.interview.rag.service.EvidenceSnapshotService;



import com.linrun.interview.common.annotation.DistributeLock;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.document.service.FileStorageService;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.constant.SegmentStatus;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.entity.KnowledgeBaseSegmentEntity;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import com.linrun.interview.chat.entity.RagChatSessionEntity;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.chat.mapper.RagSessionKnowledgeBaseMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 知识库文档管理实现（对齐业界实践 KnowledgeDocumentServiceImpl）。
 *
 * <p>负责删除级联（ES 向量 + 分段 + version + 文档）、版本激活（向量化）/失效（清向量降状态）。
 *
 * <p>与早期实现差异（遵守 ai-interview AGENTS.md）：
 * <ul>
 *   <li>主表 {@code @TableLogic} 软删；级联删除走 {@code physicalDeleteById} 物理清库。</li>
 *   <li>{@code Assert} → {@link BusinessException}（Assert 抒 IllegalArgument 绕过全局异常处理）。</li>
 *   <li>{@code deactivateVersion} 直接调 {@link VectorStoreService#removeByDocIdAndVersion}，
 *       不经 DocumentCleanupService（避免前向依赖，cleanup service 是封装层）。</li>
 *   <li>激活完成后同步 {@link KnowledgeBaseEntity} 主表 docStatus + currentVersionId。</li>
 *   <li>删除级联：ES 删向量 + MinIO 删文件移到事务提交后执行（事务内禁止外部 IO）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl
    extends ServiceImpl<KnowledgeBaseEntityMapper, KnowledgeBaseEntity>
    implements KnowledgeDocumentService {

    private static final int EMBED_PAGE_SIZE = 100;

    /** Grafana 看板依赖的向量化指标名（app.ai 前缀已开 percentiles-histogram） */
    private static final String METRIC_VECTORIZE_LATENCY = "app.ai.vectorize.document.latency";
    private static final String METRIC_EMBEDDING_INFLIGHT = "app.ai.vectorize.embedding.inflight";

    /** 正在执行嵌入批处理的文档数（注册为 gauge，跨实例聚合看并发压力） */
    private final AtomicInteger embeddingInflight = new AtomicInteger();

    private final KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;
    private final KnowledgeDocumentVersionService versionService;
    private final KnowledgeSegmentService segmentService;
    private final VectorStoreService vectorStoreService;
    private final FileStorageService fileStorageService;
    private final SegmentTextCacheService segmentTextCacheService;
    private final EvidenceSnapshotService evidenceSnapshotService;
    private final DocumentParseTaskService documentParseTaskService;
    private final RagSessionKnowledgeBaseMapper sessionKnowledgeBaseMapper;
    private final VectorizationTaskService vectorizationTaskService;
    /** 向量化三段式的编程式小事务（方法级 @Transactional 会把外部 API 调用圈进长事务） */
    private final TransactionTemplate transactionTemplate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MeterRegistry meterRegistry;

    @jakarta.annotation.PostConstruct
    void registerMetrics() {
        if (meterRegistry != null) {
            meterRegistry.gauge(METRIC_EMBEDDING_INFLIGHT, embeddingInflight);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeDocumentWithSegments(Long docId) {
        Long userId = UserContext.requireUserId();
        KnowledgeBaseEntity doc = ownedDocument(userId, docId);
        log.info("删除知识库（级联）: docId={}", docId);
        List<String> storageKeys = collectStorageKeys(doc);

        // 历史报告中的最小证据快照与源文档正文同事务脱敏，避免 DB 删除成功后仍残留正文。
        evidenceSnapshotService.markSourceUnavailable(
            userId, DataDomain.CANDIDATE, String.valueOf(docId));
        // 1. 删除所有 RAG 会话中的知识库关联（必须先删关联，否则外键约束阻止删除文档）
        removeSessionAssociations(userId, docId);
        documentParseTaskService.deleteByDocument(userId, docId);
        // 2. 物理删 segment
        segmentService.physicalDeleteByDocumentId(docId);
        // 3. 物理删 version
        versionService.physicalDeleteByDocId(docId);
        // 4. 物理删文档主表（绕过逻辑删除，与 segment/version 一致）
        knowledgeBaseEntityMapper.physicalDeleteById(docId);
        log.info("删除知识库 DB 记录完成: docId={}", docId);

        // 5. ES 删向量 + MinIO 删文件：外部 IO，移到事务提交后执行（事务内禁止外部 API）
        schedulePostCommitCleanup(docId, storageKeys);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeDocumentsWithSegments(List<Long> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return;
        }
        Long userId = UserContext.requireUserId();
        log.info("批量删除知识库（级联）: count={}", docIds.size());
        for (Long docId : docIds) {
            KnowledgeBaseEntity doc = ownedDocumentOrNull(userId, docId);
            if (doc == null) {
                log.warn("批量删除跳过不存在的知识库: docId={}", docId);
                continue;
            }
            List<String> storageKeys = collectStorageKeys(doc);
            evidenceSnapshotService.markSourceUnavailable(
                userId, DataDomain.CANDIDATE, String.valueOf(docId));
            removeSessionAssociations(userId, docId);
            documentParseTaskService.deleteByDocument(userId, docId);
            segmentService.physicalDeleteByDocumentId(docId);
            versionService.physicalDeleteByDocId(docId);
            knowledgeBaseEntityMapper.physicalDeleteById(docId);
            schedulePostCommitCleanup(docId, storageKeys);
        }
        log.info("批量删除知识库 DB 记录完成: count={}", docIds.size());
    }

    /**
     * 把 ES 删向量 + MinIO 删文件注册到当前事务的 afterCommit 回调。
     * 失败仅告警，不阻断已提交的 DB 删除（外部残留靠人工或后续清理）。
     */
    private void schedulePostCommitCleanup(
        Long docId,
        List<String> storageKeys
    ) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    vectorStoreService.removeByDocId(docId);
                } catch (Exception e) {
                    log.warn("删除ES向量失败（DB已删，需人工清理）: docId={}, error={}", docId, e.getMessage(), e);
                }
                segmentTextCacheService.evictAll();
                segmentService.evictExpansionCache();
                storageKeys.forEach(key -> deleteStorageFile(docId, key));
            }
        });
    }

    /**
     * 删除 RAG 会话中对该知识库的关联（避免外键约束阻止删除文档）。
     */
    private void removeSessionAssociations(Long userId, Long docId) {
        List<Long> sessionIds = sessionKnowledgeBaseMapper.selectSessionIdsByKnowledgeBaseId(docId);
        for (Long sessionId : sessionIds) {
            sessionKnowledgeBaseMapper.deleteLink(sessionId, docId);
        }
        if (!sessionIds.isEmpty()) {
            log.info("已从 {} 个会话中移除知识库关联: docId={}", sessionIds.size(), docId);
        }
    }

    /**
     * 删除 MinIO 中的原始文件（失败仅告警，不阻断删除）。
     */
    private void deleteStorageFile(Long docId, String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        try {
            fileStorageService.deleteKnowledgeBase(storageKey);
        } catch (Exception e) {
            log.warn("删除 MinIO 文件失败，继续删除知识库记录: docId={}, error={}", docId, e.getMessage(), e);
        }
    }

    @Override
    @DistributeLock(key = "'kb:vectorize:' + #versionId", waitTime = 0, leaseTime = -1,
        message = "该版本正在向量化，请稍后再试")
    public void activateVersion(Long versionId) {
        // 不加方法级 @Transactional：向量化含外部 API 调用，事务边界由 entity 重载内
        // 的 transactionTemplate 分段控制（事务 A / 无事务嵌入段 / 事务 B）
        KnowledgeBaseVersionEntity version = versionService.findById(versionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "版本记录不存在: versionId=" + versionId));
        requireCurrentUserOwnsDoc(version.getDocId());
        activateVersion(version);
    }

    /**
     * 校验当前登录用户拥有该知识库（防止传他人 versionId 越权 activate/deactivate/list）。
     * 仅供从 HTTP 线程发起的 (Long) 重载调用；异步/定时线程走 entity 重载，无 UserContext。
     */
    private void requireCurrentUserOwnsDoc(Long docId) {
        Long userId = UserContext.requireUserId();
        if (ownedDocumentOrNull(userId, docId) == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在");
        }
    }

    /**
     * 三段式向量化（无方法级事务，消灭「嵌入外部 API 占住 DB 连接的分钟级长事务」）：
     * <ol>
     *   <li>事务 A：旧当前版本 DB 降级（segment 降 STORED + 版本降 CHUNKED），ES 清理在事务外；</li>
     *   <li>无事务段：分页嵌入写 ES，每批用独立小事务批量回写 embeddingId/status，
     *       批内 DB 回写失败按本批 embeddingIds 反向删 ES（消灭孤儿向量）；</li>
     *   <li>事务 B：版本 + 主表状态推进 VECTOR_STORED。</li>
     * </ol>
     * 任一批失败版本停留 CHUNKED，补偿任务重扫时跳过已有 embeddingId 的分段（幂等）。
     */
    @Override
    @DistributeLock(key = "'kb:vectorize:' + #version.versionId", waitTime = 0, leaseTime = -1,
        message = "该版本正在向量化，请稍后再试")
    public void activateVersion(KnowledgeBaseVersionEntity version) {
        if (version == null || version.getVersionId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "版本不能为空");
        }
        Long requestedVersionId = version.getVersionId();
        // 事件、补偿和手动激活都可能携带旧快照。拿到版本级锁后重新读取，避免使用过期状态
        // 重复执行或覆盖已完成结果。
        KnowledgeBaseVersionEntity latestVersion = versionService.findById(requestedVersionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "版本记录不存在: versionId=" + requestedVersionId));
        // 幂等：已向量化直接返回
        if (latestVersion.getStatus() == DocumentStatus.VECTOR_STORED) {
            log.info("版本已向量化，跳过: versionId={}", latestVersion.getVersionId());
            return;
        }
        if (latestVersion.getStatus() != DocumentStatus.CHUNKED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "版本状态不是 CHUNKED，无法执行向量化，当前状态: " + latestVersion.getStatus());
        }
        VectorizationTaskService.Claim claim = vectorizationTaskService.claim(requestedVersionId);
        if (claim.state() != VectorizationTaskService.ClaimState.ACQUIRED
            || claim.version() == null) {
            if (claim.state() == VectorizationTaskService.ClaimState.TERMINAL
                && claim.version() != null
                && claim.version().getStatus() == DocumentStatus.VECTOR_STORED) {
                return;
            }
            String lastError = claim.version() == null ? null : claim.version().getEmbeddingLastError();
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化任务暂不可执行: state=" + claim.state()
                    + (lastError == null ? "" : ", lastError=" + lastError));
        }
        try {
            activateClaimedVersion(claim.version());
        } catch (Exception e) {
            try {
                if (!vectorizationTaskService.fail(claim.version(), e)) {
                    log.warn("向量化任务租约已失效，忽略旧任务失败状态: versionId={}", requestedVersionId);
                }
            } catch (Exception persistenceFailure) {
                log.error("记录向量化失败状态异常: versionId={}", requestedVersionId,
                    persistenceFailure);
            }
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "版本向量化失败: versionId=" + requestedVersionId, e);
        }
    }

    private void activateClaimedVersion(KnowledgeBaseVersionEntity latestVersion) {
        Long docId = latestVersion.getDocId();
        Long versionId = latestVersion.getVersionId();
        log.info("开始向量化版本: docId={}, versionId={}", docId, versionId);

        KnowledgeBaseEntity doc = Optional.ofNullable(knowledgeBaseEntityMapper.selectById(docId))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "知识库不存在: docId=" + docId));

        // 事务 A：失效文档当前激活版本（DB 降级入事务，ES 清理在事务提交后执行）
        Long currentVersionId = doc.getCurrentVersionId();
        if (currentVersionId != null && !currentVersionId.equals(versionId)) {
            KnowledgeBaseVersionEntity oldVersion = versionService.findById(currentVersionId).orElse(null);
            if (oldVersion != null && oldVersion.getStatus() == DocumentStatus.VECTOR_STORED) {
                transactionTemplate.executeWithoutResult(tx -> {
                    segmentService.downgradeStatus(docId, currentVersionId,
                        SegmentStatus.VECTOR_STORED, SegmentStatus.STORED);
                    oldVersion.setStatus(DocumentStatus.CHUNKED);
                    versionService.updateVersion(oldVersion);
                });
                // ES 删除失败抛异常阻断激活：目标版本停 CHUNKED，补偿任务下轮重试
                vectorStoreService.removeByDocIdAndVersion(docId, currentVersionId);
            }
        }

        // 无事务段：分页扫 STORED + skipEmbedding=0 + embeddingId IS NULL 的分段，
        // 嵌入（外部 API）+ ES 写入后，每批独立小事务一条 UPDATE 批量回写
        long vectorizeStartNanos = System.nanoTime();
        embeddingInflight.incrementAndGet();
        try {
            List<KnowledgeBaseSegmentEntity> batch;
            do {
                batch = segmentService.listPendingEmbedding(
                    docId, versionId, SegmentStatus.STORED, EMBED_PAGE_SIZE);
                if (batch.isEmpty()) {
                    break;
                }
                if (!vectorizationTaskService.renew(latestVersion)) {
                    throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                        "向量化任务租约已失效，停止写入 ES: versionId=" + versionId);
                }
                String embeddingClaim = embeddingClaimToken(latestVersion);
                List<String> embeddingIds = vectorStoreService.embedAndStore(batch, embeddingClaim);
                for (int i = 0; i < batch.size(); i++) {
                    KnowledgeBaseSegmentEntity seg = batch.get(i);
                    seg.setEmbeddingId(embeddingIds.get(i));
                    seg.setStatus(SegmentStatus.VECTOR_STORED);
                }
                try {
                    int affected = segmentService.batchUpdateEmbedding(
                        docId, versionId,
                        Math.max(latestVersion.getEmbeddingAttempt(), 0),
                        latestVersion.getEmbeddingClaimedAt(),
                        batch);
                    if (affected != batch.size()) {
                        throw new BusinessException(
                            ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                            "文档或版本已删除，停止回写向量: docId=" + docId
                                + ", versionId=" + versionId
                                + ", expected=" + batch.size()
                                + ", affected=" + affected);
                    }
                } catch (Exception e) {
                    // 只删除仍携带本批租约令牌的向量；新任务覆盖稳定 ID 后令牌已变化，
                    // 旧任务无法误删新向量。
                    log.error("分段 embeddingId 批量回写失败，反向清理本批 ES 向量: docId={}, versionId={}, batchSize={}",
                        docId, versionId, batch.size(), e);
                    try {
                        vectorStoreService.removeByEmbeddingClaim(embeddingClaim);
                    } catch (Exception cleanupEx) {
                        log.error("反向清理本批 ES 向量失败，留待后续重试覆盖: docId={}, versionId={}",
                            docId, versionId, cleanupEx);
                    }
                    if (e instanceof BusinessException be) {
                        throw be;
                    }
                    throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                        "分段 embeddingId 回写失败: docId=" + docId, e);
                }
            } while (batch.size() == EMBED_PAGE_SIZE);
        } finally {
            embeddingInflight.decrementAndGet();
            if (meterRegistry != null) {
                meterRegistry.timer(METRIC_VECTORIZE_LATENCY)
                    .record(System.nanoTime() - vectorizeStartNanos, TimeUnit.NANOSECONDS);
            }
        }

        // 事务 B：版本 + 主表状态推进 VECTOR_STORED
        transactionTemplate.executeWithoutResult(tx -> {
            if (!vectorizationTaskService.complete(latestVersion)) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "向量化任务租约已失效，拒绝旧任务完成状态: versionId=" + versionId);
            }
            doc.setDocStatus(DocumentStatus.VECTOR_STORED);
            doc.setCurrentVersionId(versionId);
            if (knowledgeBaseEntityMapper.updateById(doc) != 1) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "文档状态已变化，拒绝旧任务推进终态: docId=" + docId);
            }
        });
        log.info("版本向量化完成: docId={}, versionId={}", docId, versionId);
    }

    private String embeddingClaimToken(KnowledgeBaseVersionEntity version) {
        if (version.getEmbeddingClaimedAt() == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化任务缺少租约令牌: versionId=" + version.getVersionId());
        }
        return version.getVersionId() + ":"
            + Math.max(version.getEmbeddingAttempt(), 0) + ":"
            + version.getEmbeddingClaimedAt();
    }

    private List<String> collectStorageKeys(KnowledgeBaseEntity document) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (document.getStorageKey() != null && !document.getStorageKey().isBlank()) {
            keys.add(document.getStorageKey());
        }
        versionService.listByDocId(document.getId()).stream()
            .map(KnowledgeBaseVersionEntity::getStorageKey)
            .filter(key -> key != null && !key.isBlank())
            .forEach(keys::add);
        return List.copyOf(keys);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deactivateVersion(Long versionId) {
        KnowledgeBaseVersionEntity version = versionService.findById(versionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "版本记录不存在: versionId=" + versionId));
        requireCurrentUserOwnsDoc(version.getDocId());
        if (version.getStatus() == DocumentStatus.CHUNKED) {
            log.info("版本已失效（CHUNKED），跳过: versionId={}", versionId);
            return;
        }
        if (version.getStatus() != DocumentStatus.VECTOR_STORED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "版本状态不是 VECTOR_STORED，无法失效，当前状态: " + version.getStatus());
        }
        deactivateVersionInternal(version);
        log.info("版本失效完成: versionId={}", versionId);
    }

    /**
     * 失效版本的内部实现，不做状态前置校验（调用方已校验），供 activateVersion 切换时复用。
     * 语义：清 ES 向量（docId+versionId）→ segment 降 STORED → 版本降 CHUNKED。
     */
    private void deactivateVersionInternal(KnowledgeBaseVersionEntity version) {
        Long docId = version.getDocId();
        Long versionId = version.getVersionId();
        log.info("失效版本: docId={}, versionId={}", docId, versionId);
        // 1. 按 docId + versionId 删 ES 向量
        vectorStoreService.removeByDocIdAndVersion(docId, versionId);
        // 2. 分段降级 VECTOR_STORED → STORED + 清空 embeddingId
        segmentService.downgradeStatus(docId, versionId,
            SegmentStatus.VECTOR_STORED, SegmentStatus.STORED);
        // 3. 版本降 CHUNKED
        version.setStatus(DocumentStatus.CHUNKED);
        versionService.updateVersion(version);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean advanceDocumentAndVersionStatus(Long docId, Long versionId, DocumentStatus targetStatus) {
        if (docId == null || versionId == null || targetStatus == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "推进文档状态参数不能为空");
        }
        KnowledgeBaseEntity document = Optional.ofNullable(knowledgeBaseEntityMapper.selectById(docId))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: docId=" + docId));
        KnowledgeBaseVersionEntity version = versionService.findById(versionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "版本记录不存在: versionId=" + versionId));
        if (!docId.equals(version.getDocId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "版本不属于该知识库");
        }

        boolean updated = false;
        if (shouldAdvanceStatus(document.getDocStatus(), targetStatus)) {
            document.setDocStatus(targetStatus);
            knowledgeBaseEntityMapper.updateById(document);
            updated = true;
            log.info("文档状态已推进: docId={}, status={}", docId, targetStatus);
        } else {
            log.debug("文档状态无需推进: docId={}, current={}, target={}",
                docId, document.getDocStatus(), targetStatus);
        }

        if (shouldAdvanceStatus(version.getStatus(), targetStatus)) {
            version.setStatus(targetStatus);
            versionService.updateVersion(version);
            updated = true;
            log.info("版本状态已推进: versionId={}, status={}", versionId, targetStatus);
        } else {
            log.debug("版本状态无需推进: versionId={}, current={}, target={}",
                versionId, version.getStatus(), targetStatus);
        }
        return updated;
    }

    private boolean shouldAdvanceStatus(DocumentStatus current, DocumentStatus target) {
        if (current == null) {
            return true;
        }
        return current.ordinal() < target.ordinal();
    }

    private KnowledgeBaseEntity ownedDocument(Long userId, Long docId) {
        KnowledgeBaseEntity document = ownedDocumentOrNull(userId, docId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId);
        }
        return document;
    }

    private KnowledgeBaseEntity ownedDocumentOrNull(Long userId, Long docId) {
        return knowledgeBaseEntityMapper.selectOne(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
            .eq(KnowledgeBaseEntity::getUserId, userId)
            .eq(KnowledgeBaseEntity::getId, docId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void switchActiveVersion(Long docId, Long targetVersionId) {
        KnowledgeBaseEntity doc = Optional.ofNullable(knowledgeBaseEntityMapper.selectById(docId))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: docId=" + docId));
        KnowledgeBaseVersionEntity target = versionService.findById(targetVersionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "版本记录不存在: versionId=" + targetVersionId));
        if (target.getStatus() != DocumentStatus.VECTOR_STORED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "目标版本未向量化，无法热切换，当前状态: " + target.getStatus());
        }

        // 旧版本 DB 降级与主表指针更新在同一事务内，保证切换原子性
        Long oldVersionId = doc.getCurrentVersionId();
        if (oldVersionId != null && !oldVersionId.equals(targetVersionId)) {
            versionService.findById(oldVersionId)
                .filter(old -> old.getStatus() == DocumentStatus.VECTOR_STORED)
                .ifPresent(old -> {
                    segmentService.downgradeStatus(docId, oldVersionId,
                        SegmentStatus.VECTOR_STORED, SegmentStatus.STORED);
                    old.setStatus(DocumentStatus.CHUNKED);
                    versionService.updateVersion(old);
                    // ES 清理是外部 IO，注册到事务提交后执行；失败留给向量对账任务兜底
                    scheduleVectorCleanupAfterCommit(docId, oldVersionId);
                });
        }
        doc.setCurrentVersionId(targetVersionId);
        doc.setDocStatus(DocumentStatus.VECTOR_STORED);
        knowledgeBaseEntityMapper.updateById(doc);
        log.info("热切换激活版本完成: docId={}, oldVersionId={}, targetVersionId={}",
            docId, oldVersionId, targetVersionId);
    }

    private void scheduleVectorCleanupAfterCommit(Long docId, Long versionId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    vectorStoreService.removeByDocIdAndVersion(docId, versionId);
                } catch (Exception e) {
                    log.warn("切换版本后清理旧版本ES向量失败（DB已切换），留待向量对账兜底: docId={}, versionId={}",
                        docId, versionId, e);
                }
            }
        });
    }
}
