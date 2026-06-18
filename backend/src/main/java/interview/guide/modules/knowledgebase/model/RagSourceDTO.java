package interview.guide.modules.knowledgebase.model;

/**
 * RAG 问答引用来源。
 *
 * @param cited 该来源是否被回答正文以 [n] 编号实际引用，用于校验模型是否真用到了检索内容
 */
public record RagSourceDTO(
    Long knowledgeBaseId,
    String documentTitle,
    String sourceName,
    String category,
    String sectionTitle,
    Integer chunkIndex,
    Integer chunkCount,
    String snippet,
    Double similarity,
    boolean cited
) {}
