package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.infrastructure.file.FileStorageService;
import com.linrun.interview.modules.knowledgebase.constant.DocumentStatus;
import com.linrun.interview.modules.knowledgebase.constant.SegmentStatus;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import com.linrun.interview.modules.knowledgebase.model.RagChatSessionEntity;
import com.linrun.interview.modules.knowledgebase.repository.KnowledgeBaseRepository;
import com.linrun.interview.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 知识库文档管理实现（对齐 know-engine KnowledgeDocumentServiceImpl）。
 *
 * <p>负责删除级联（ES 向量 + segment + version + 文档）、版本激活（向量化）/失效（清向量降状态）。
 *
 * <p>与 know-engine 差异（遵守 ai-interview AGENTS.md）：
 * <ul>
 *   <li>MyBatis-Plus → JPA：物理删除用 {@code @Modifying @Query}（JPA 无 @TableLogic，deleteById 即物理删）。</li>
 *   <li>{@code Assert} → {@link BusinessException}（Assert 抒 IllegalArgument 绕过全局异常处理）。</li>
 *   <li>{@code deactivateVersion} 直接调 {@link VectorStoreService#removeByDocIdAndVersion}，
 *       不经 DocumentCleanupService（避免前向依赖，cleanup service 是封装层）。</li>
 *   <li>激活完成后同步 {@link KnowledgeBaseEntity} 主表 docStatus + currentVersionId。</li>
 *   <li>删除级联：ES 删向量 + RustFS 删文件移到事务提交后执行（事务内禁止外部 IO）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private static final int EMBED_PAGE_SIZE = 100;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentVersionService versionService;
    private final KnowledgeSegmentService segmentService;
    private final VectorStoreService vectorStoreService;
    private final FileStorageService fileStorageService;
    private final RagChatSessionRepository sessionRepository;
    private final KnowledgeBaseDataTableService dataTableService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeDocumentWithSegments(Long docId) {
        Long userId = UserContext.requireUserId();
        KnowledgeBaseEntity doc = knowledgeBaseRepository.findByUserIdAndId(userId, docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId));
        log.info("删除知识库（级联）: docId={}", docId);

        // 1. 删除所有 RAG 会话中的知识库关联（必须先删关联，否则外键约束阻止删除文档）
        removeSessionAssociations(userId, docId);
        // 2. 删除动态数据表（Excel/CSV DATA_QUERY）
        dataTableService.deleteByDoc(userId, docId);
        // 3. 物理删 segment
        segmentService.physicalDeleteByDocumentId(docId);
        // 4. 物理删 version
        versionService.physicalDeleteByDocId(docId);
        // 5. 物理删文档
        knowledgeBaseRepository.deleteById(docId);
        log.info("删除知识库 DB 记录完成: docId={}", docId);

        // 5. ES 删向量 + RustFS 删文件：外部 IO，移到事务提交后执行（事务内禁止外部 API）
        schedulePostCommitCleanup(docId, doc.getStorageKey());
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
            KnowledgeBaseEntity doc = knowledgeBaseRepository.findByUserIdAndId(userId, docId)
                .orElse(null);
            if (doc == null) {
                log.warn("批量删除跳过不存在的知识库: docId={}", docId);
                continue;
            }
            removeSessionAssociations(userId, docId);
            dataTableService.deleteByDoc(userId, docId);
            segmentService.physicalDeleteByDocumentId(docId);
            versionService.physicalDeleteByDocId(docId);
            knowledgeBaseRepository.deleteById(docId);
            schedulePostCommitCleanup(docId, doc.getStorageKey());
        }
        log.info("批量删除知识库 DB 记录完成: count={}", docIds.size());
    }

    /**
     * 把 ES 删向量 + RustFS 删文件注册到当前事务的 afterCommit 回调。
     * 失败仅告警，不阻断已提交的 DB 删除（外部残留靠人工或后续清理）。
     */
    private void schedulePostCommitCleanup(Long docId, String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    vectorStoreService.removeByDocId(docId);
                } catch (Exception e) {
                    log.warn("删除ES向量失败（DB已删，需人工清理）: docId={}, error={}", docId, e.getMessage(), e);
                }
                deleteStorageFile(docId, storageKey);
            }
        });
    }

    /**
     * 删除 RAG 会话中对该知识库的关联（避免外键约束阻止删除文档）。
     */
    private void removeSessionAssociations(Long userId, Long docId) {
        List<RagChatSessionEntity> sessions =
            sessionRepository.findByUserIdAndKnowledgeBaseIds(userId, List.of(docId));
        for (RagChatSessionEntity session : sessions) {
            session.getKnowledgeBases().removeIf(kb -> kb.getId().equals(docId));
            sessionRepository.save(session);
        }
        if (!sessions.isEmpty()) {
            log.info("已从 {} 个会话中移除知识库关联: docId={}", sessions.size(), docId);
        }
    }

    /**
     * 删除 RustFS 中的原始文件（失败仅告警，不阻断删除）。
     */
    private void deleteStorageFile(Long docId, String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        try {
            fileStorageService.deleteKnowledgeBase(storageKey);
        } catch (Exception e) {
            log.warn("删除RustFS文件失败，继续删除知识库记录: docId={}, error={}", docId, e.getMessage(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void activateVersion(Long versionId) {
        KnowledgeBaseVersionEntity version = versionService.getById(versionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "版本记录不存在: versionId=" + versionId));
        activateVersion(version);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void activateVersion(KnowledgeBaseVersionEntity version) {
        // 幂等：已向量化直接返回
        if (version.getStatus() == DocumentStatus.VECTOR_STORED) {
            log.info("版本已向量化，跳过: versionId={}", version.getVersionId());
            return;
        }
        if (version.getStatus() != DocumentStatus.CHUNKED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "版本状态不是 CHUNKED，无法执行向量化，当前状态: " + version.getStatus());
        }
        Long docId = version.getDocId();
        Long versionId = version.getVersionId();
        log.info("开始向量化版本: docId={}, versionId={}", docId, versionId);

        // 切换/重新向量化前，先失效文档当前激活版本的 ES 向量（避免旧版本向量残留干扰检索）
        KnowledgeBaseEntity doc = knowledgeBaseRepository.findById(docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "知识库不存在: docId=" + docId));
        Long currentVersionId = doc.getCurrentVersionId();
        if (currentVersionId != null && !currentVersionId.equals(versionId)) {
            versionService.getById(currentVersionId).ifPresent(this::deactivateVersionInternal);
        }

        // 分页扫 STORED + skipEmbedding=0 + embeddingId IS NULL 的分段
        Page<KnowledgeBaseSegmentEntity> page = segmentService.pagePendingEmbedding(
            docId, versionId, SegmentStatus.STORED, PageRequest.of(0, EMBED_PAGE_SIZE));
        while (!page.isEmpty()) {
            List<KnowledgeBaseSegmentEntity> batch = page.getContent();
            List<String> embeddingIds = vectorStoreService.embedAndStore(batch);
            for (int i = 0; i < batch.size(); i++) {
                KnowledgeBaseSegmentEntity seg = batch.get(i);
                seg.setEmbeddingId(embeddingIds.get(i));
                seg.setStatus(SegmentStatus.VECTOR_STORED);
                segmentService.update(seg);
            }
            page = segmentService.pagePendingEmbedding(
                docId, versionId, SegmentStatus.STORED, PageRequest.of(0, EMBED_PAGE_SIZE));
        }

        // 版本升 VECTOR_STORED
        version.setStatus(DocumentStatus.VECTOR_STORED);
        versionService.update(version);

        // 同步文档主表
        doc.setDocStatus(DocumentStatus.VECTOR_STORED);
        doc.setCurrentVersionId(versionId);
        knowledgeBaseRepository.save(doc);
        log.info("版本向量化完成: docId={}, versionId={}", docId, versionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deactivateVersion(Long versionId) {
        KnowledgeBaseVersionEntity version = versionService.getById(versionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "版本记录不存在: versionId=" + versionId));
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
        versionService.update(version);
    }
}
