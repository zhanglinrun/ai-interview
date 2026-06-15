package interview.guide.modules.knowledgebase.model;

/**
 * RAG 问答引用来源。
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
    Double similarity
) {}
