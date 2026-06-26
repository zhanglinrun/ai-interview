package com.linrun.interview.modules.knowledgebase.constant;

/**
 * 知识库分段状态机（对齐 know-engine SegmentStatus）。
 */
public enum SegmentStatus {
    /** 关系型数据库存储完成（segment 已落库，尚未写向量）。 */
    STORED,
    /** 向量数据库存储完成（已写 ES，回写 embeddingId）。 */
    VECTOR_STORED
}
