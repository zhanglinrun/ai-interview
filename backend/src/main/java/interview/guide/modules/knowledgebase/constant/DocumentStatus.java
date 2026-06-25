package interview.guide.modules.knowledgebase.constant;

/**
 * 知识库文档状态机（对齐 know-engine DocumentStatus）。
 *
 * <p>流转：{@link #INIT} → {@link #UPLOADED} → {@link #CONVERTING} → {@link #CONVERTED}
 * → {@link #CHUNKED} → {@link #VECTOR_STORED}。无需向量存储的走 {@link #STORED}。
 *
 * <p>与旧 {@code VectorStatus}（PENDING/PROCESSING/COMPLETED/FAILED）不同：本状态机无显式
 * FAILED，失败靠文档停在 {@link #CHUNKED} 由 {@code @Scheduled} 补偿任务重试。
 */
public enum DocumentStatus {
    /** 初始状态。 */
    INIT,
    /** 上传完成。 */
    UPLOADED,
    /** 转换中（解析为 Markdown）。 */
    CONVERTING,
    /** 转换完成。 */
    CONVERTED,
    /** 分块完成（segment 已落库，待向量化）。 */
    CHUNKED,
    /** 向量存储完成。 */
    VECTOR_STORED,
    /** 存储完成（不需要向量存储）。 */
    STORED
}
