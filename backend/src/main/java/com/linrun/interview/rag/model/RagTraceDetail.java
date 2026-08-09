package com.linrun.interview.rag.model;

import java.util.List;

/** 面向 Trace 页面和评测回放的结构化 trace 视图。 */
public record RagTraceDetail(
    RagTraceRunEntity run,
    List<RagTraceStageEntity> stages,
    List<RagTraceCandidateEntity> candidates,
    List<RagTraceCitationEntity> citations,
    RagAnswerSnapshotEntity answer
) {
}
