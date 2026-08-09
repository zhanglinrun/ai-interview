package com.linrun.interview.document.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.annotation.DistributeLock;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.document.service.DocumentCleanupService;
import com.linrun.interview.document.service.KnowledgeDocumentService;
import com.linrun.interview.document.service.KnowledgeDocumentVersionService;
import com.linrun.interview.document.service.KnowledgeSegmentService;
import com.linrun.interview.document.service.VectorizationTaskService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档处理补偿任务（对齐业界实践 DocumentCompensationJob）。
 *
 * <p>{@link Scheduled} 调度 + {@link DistributeLock}（waitTime=0）防多实例重复执行：
 * 同一时刻只有一个实例真正跑补偿，其余实例拿不到锁直接跳过本轮。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentCompensationJob {

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeDocumentVersionService versionService;
    private final KnowledgeSegmentService segmentService;
    private final KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;
    private final DocumentCleanupService documentCleanupService;
    private final VectorizationTaskService vectorizationTaskService;

    /**
     * 向量化补偿任务：分页扫描达到重试时间、租约空闲且未终止的 CHUNKED 当前版本。
     * 默认每 5 分钟执行一次；真正的 CAS 抢占、指数退避和最大次数仍由
     * {@link VectorizationTaskService} 统一控制。
     */
    @Scheduled(fixedDelayString = "${app.knowledgebase.compensation.embedding-delay-ms:300000}",
        initialDelayString = "${app.knowledgebase.compensation.embedding-initial-delay-ms:60000}")
    @XxlJob("ragEmbeddingCompensation")
    @DistributeLock(key = "'kb:compensation:embedding'", waitTime = 0, leaseTime = 600,
        message = "向量化补偿任务已在其他实例执行")
    public void runEmbeddingCompensation() {
        log.info("========== 开始执行向量化补偿任务 ==========");
        int successCount = 0;
        int failCount = 0;
        try {
            List<KnowledgeBaseVersionEntity> versions = vectorizationTaskService.findRecoverable();
            log.info("发现 {} 个达到重试时间的 CHUNKED 版本", versions.size());

            for (KnowledgeBaseVersionEntity version : versions) {
                Long docId = version.getDocId();
                Long versionId = version.getVersionId();
                KnowledgeBaseEntity doc = knowledgeBaseEntityMapper.selectById(docId);
                if (doc == null) {
                    log.warn("文档不存在，跳过补偿: docId={}", docId);
                    continue;
                }
                // 只补偿当前版本（非当前版本由 retryFailedCleanups 清理）
                if (!versionId.equals(doc.getCurrentVersionId())) {
                    log.debug("非当前版本，跳过向量化补偿: docId={}, versionId={}", docId, versionId);
                    continue;
                }
                try {
                    knowledgeDocumentService.activateVersion(version);
                    log.info("向量化补偿成功: docId={}, versionId={}", docId, versionId);
                    successCount++;
                } catch (Exception e) {
                    log.error("向量化补偿失败: docId={}, versionId={}", docId, versionId, e);
                    failCount++;
                }
            }
        } catch (Exception e) {
            log.error("向量化补偿任务执行异常", e);
        }
        log.info("========== 向量化补偿任务完成，成功: {}，失败: {} ==========", successCount, failCount);
    }

    /**
     * 旧版本清理补偿任务：扫描 VECTOR_STORED + 有残留旧版本 segment 的文档，清理旧版本数据。
     * 每 30 分钟执行一次。版本切换/上传新版本后旧版本残留数据由本任务清理。
     */
    @Scheduled(fixedDelayString = "${app.knowledgebase.compensation.cleanup-delay-ms:1800000}",
        initialDelayString = "${app.knowledgebase.compensation.cleanup-initial-delay-ms:120000}")
    @XxlJob("ragVersionCleanupCompensation")
    @DistributeLock(key = "'kb:compensation:cleanup'", waitTime = 0, leaseTime = 600,
        message = "旧版本清理补偿任务已在其他实例执行")
    public void runFailedCleanups() {
        log.info("========== 开始执行旧版本清理补偿任务 ==========");
        try {
            List<KnowledgeBaseEntity> docs = knowledgeBaseEntityMapper.selectList(
                Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                    .eq(KnowledgeBaseEntity::getDocStatus, DocumentStatus.VECTOR_STORED)
                    .isNotNull(KnowledgeBaseEntity::getCurrentVersionId));
            int cleanedCount = 0;
            for (KnowledgeBaseEntity doc : docs) {
                Long currentVersionId = doc.getCurrentVersionId();
                long staleCount = segmentService.countStaleByDocumentId(doc.getId(), currentVersionId);
                if (staleCount > 0) {
                    log.info("文档 {} 有 {} 个残留旧版本分段，开始清理", doc.getId(), staleCount);
                    // 清理所有非当前版本
                    List<KnowledgeBaseVersionEntity> versions = versionService.listByDocId(doc.getId());
                    for (KnowledgeBaseVersionEntity v : versions) {
                        if (!v.getVersionId().equals(currentVersionId)) {
                            try {
                                documentCleanupService.cleanupOldVersionData(doc.getId(), v.getVersionId());
                                cleanedCount++;
                            } catch (Exception e) {
                                log.error("清理旧版本失败: docId={}, versionId={}", doc.getId(), v.getVersionId(), e);
                            }
                        }
                    }
                }
            }
            log.info("========== 旧版本清理补偿任务完成，清理版本数: {} ==========", cleanedCount);
        } catch (Exception e) {
            log.error("旧版本清理补偿任务执行异常", e);
        }
    }
}
