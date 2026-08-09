package com.linrun.interview.document.event;
import com.linrun.interview.document.service.DocumentProcessService;


import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;

/**
 * 知识库文档切块完成事件（对齐业界实践 DocumentChunkedEvent）。
 *
 * <p>由 {@code DocumentProcessService.split} 在事务内切块落库 + 状态置 CHUNKED 后发布。
 * {@link DocumentEventListener} 以 {@code @Async} + {@code AFTER_COMMIT} 监听，
 * 保证切块事务提交后才触发向量化，避免异步线程读到旧 status。
 */
public class DocumentChunkedEvent {

    private final Long docId;
    private final Long versionId;
    private final int segmentCount;

    public DocumentChunkedEvent(Long docId, Long versionId, int segmentCount) {
        this.docId = docId;
        this.versionId = versionId;
        this.segmentCount = segmentCount;
    }

    public DocumentChunkedEvent(KnowledgeBaseVersionEntity version, int segmentCount) {
        this(version.getDocId(), version.getVersionId(), segmentCount);
    }

    public Long getDocId() {
        return docId;
    }

    public Long getVersionId() {
        return versionId;
    }

    public int getSegmentCount() {
        return segmentCount;
    }
}
