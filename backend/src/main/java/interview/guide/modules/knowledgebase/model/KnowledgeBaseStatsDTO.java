package interview.guide.modules.knowledgebase.model;

/**
 * 知识库统计信息DTO
 */
public record KnowledgeBaseStatsDTO(
    long totalCount,           // 知识库总数
    long totalQuestionCount,   // 总提问次数
    long totalAccessCount,     // 总访问次数
    long completedCount,       // 已向量化完成（docStatus=VECTOR_STORED）数量
    long processingCount       // 处理中（docStatus in CONVERTED/CHUNKED）数量
) {
}
