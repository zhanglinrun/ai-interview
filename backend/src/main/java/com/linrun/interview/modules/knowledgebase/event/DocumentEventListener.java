package com.linrun.interview.modules.knowledgebase.event;

import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseVersionMapper;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * 文档切块事件监听器（对齐 know-engine DocumentEventListener）。
 *
 * <p>监听 {@link DocumentChunkedEvent}，在切块事务 {@code AFTER_COMMIT} 阶段以 {@code @Async}
 * 异步触发向量化（{@link KnowledgeDocumentService#activateVersion}）。{@code AFTER_COMMIT} 保证
 * 切块事务提交后才执行，避免异步线程读到旧 status；{@code @Async} 用 {@code eventListenerExecutor}
 * 线程池，不阻塞切块请求。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentEventListener {

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeBaseVersionMapper versionRepository;

    @Async("eventListenerExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentChunked(DocumentChunkedEvent event) {
        Long versionId = event.getVersionId();
        Long docId = event.getDocId();
        log.info("收到文档切块事件，触发向量化: docId={}, versionId={}, segmentCount={}",
            docId, versionId, event.getSegmentCount());
        try {
            Optional<KnowledgeBaseVersionEntity> versionOpt =
                Optional.ofNullable(versionRepository.selectById(versionId));
            if (versionOpt.isEmpty()) {
                log.warn("版本不存在，跳过向量化: versionId={}", versionId);
                return;
            }
            KnowledgeBaseVersionEntity version = versionOpt.get();
            // 处理前校验实体是否仍存在（版本可能在事件投递期间被删）
            knowledgeDocumentService.activateVersion(version);
            log.info("文档向量化完成: docId={}, versionId={}", docId, versionId);
        } catch (Exception e) {
            // 失败不重抛：状态停在 CHUNKED，由 @Scheduled 补偿任务兜底重试
            log.error("文档向量化失败，等待补偿任务重试: docId={}, versionId={}, error={}",
                docId, versionId, e.getMessage(), e);
        }
    }
}
