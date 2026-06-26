package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.UserContext;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.knowledgebase.constant.DocumentStatus;
import interview.guide.modules.knowledgebase.constant.SegmentStatus;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeDocumentWithSegments(Long docId) {
        Long userId = UserContext.requireUserId();
        KnowledgeBaseEntity doc = knowledgeBaseRepository.findByUserIdAndId(userId, docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId));
        log.info("删除知识库（级联）: docId={}", docId);

        // 1. 删除所有 RAG 会话中的知识库关联（必须先删关联，否则外键约束阻止删除文档）
        removeSessionAssociations(userId, docId);

        // 2. 按 docId 删 ES 向量（失败仅告警，不阻断 DB 删除）
        vectorStoreService.removeByDocId(docId);
        // 3. 物理删 segment
        segmentService.physicalDeleteByDocumentId(docId);
        // 4. 物理删 version
        versionService.physicalDeleteByDocId(docId);
        // 5. 删 RustFS 原始文件（失败仅告警，不阻断删除）
        deleteStorageFile(docId, doc.getStorageKey());
        // 6. 物理删文档
        knowledgeBaseRepository.deleteById(docId);
        log.info("删除知识库完成: docId={}", docId);
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
            vectorStoreService.removeByDocId(docId);
            segmentService.physicalDeleteByDocumentId(docId);
            versionService.physicalDeleteByDocId(docId);
            deleteStorageFile(docId, doc.getStorageKey());
            knowledgeBaseRepository.deleteById(docId);
        }
        log.info("批量删除知识库完成: count={}", docIds.size());
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
        KnowledgeBaseEntity doc = knowledgeBaseRepository.findById(docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "知识库不存在: docId=" + docId));
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
        Long docId = version.getDocId();
        log.info("开始失效版本: docId={}, versionId={}", docId, versionId);

        // 1. 按 docId + versionId 删 ES 向量
        vectorStoreService.removeByDocIdAndVersion(docId, versionId);
        // 2. 分段降级 VECTOR_STORED → STORED + 清空 embeddingId
        segmentService.downgradeStatus(docId, versionId,
            SegmentStatus.VECTOR_STORED, SegmentStatus.STORED);
        // 3. 版本降 CHUNKED
        version.setStatus(DocumentStatus.CHUNKED);
        versionService.update(version);
        log.info("版本失效完成: versionId={}", versionId);
    }
}
