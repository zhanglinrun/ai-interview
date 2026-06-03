package interview.guide.modules.knowledgebase.model;

/**
 * RAG 问答引用来源。
 */
public record RagSourceDTO(
    Long knowledgeBaseId,
    String documentTitle,
    String snippet,
    Double similarity
) {}
