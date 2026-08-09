package com.linrun.interview.business.service;

import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.document.service.FileStorageService;
import com.linrun.interview.business.service.InterviewPersistenceService;
import com.linrun.interview.rag.service.EvidenceSnapshotService;
import com.linrun.interview.business.entity.ResumeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 简历删除服务
 * 处理简历删除的业务逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeDeleteService {
    
    private final ResumePersistenceService persistenceService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final FileStorageService storageService;
    private final EvidenceSnapshotService evidenceSnapshotService;
    
    /**
     * 删除简历
     * 
     * @param id 简历ID
     * @throws com.linrun.interview.common.exception.BusinessException 如果简历不存在
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteResume(Long id) {
        log.info("收到删除简历请求: id={}", id);
        
        // 获取简历信息（用于删除存储文件）
        ResumeEntity resume = persistenceService.findById(id)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.RESUME_NOT_FOUND));
        
        // 1. 历史报告保留结论，但同事务清除冻结的简历正文与原始定位。
        evidenceSnapshotService.markSourceUnavailable(
            resume.getUserId(), DataDomain.CANDIDATE, "resume:" + id);

        // 2. 删除面试会话及其原始可回放数据。
        interviewPersistenceService.deleteSessionsByResumeId(id);
        
        // 3. 删除数据库记录（包括分析记录）
        persistenceService.deleteResume(id);

        // 4. MinIO 是外部系统，只在数据库提交后删除；失败保留可审计日志，不占用长事务。
        scheduleStorageDeletion(resume.getStorageKey(), id);
        
        log.info("简历删除完成: id={}", id);
    }

    private void scheduleStorageDeletion(String storageKey, Long resumeId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storageService.deleteResume(storageKey);
                } catch (Exception e) {
                    log.warn("删除简历存储文件失败（DB 已删，需重试）: resumeId={}", resumeId, e);
                }
            }
        });
    }
}

