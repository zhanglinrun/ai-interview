package interview.guide.modules.knowledgebase.model;

import java.util.List;

/**
 * 知识库查询响应。
 *
 * @param confidence         综合置信度（平均相似度 × (1-覆盖率权重) + 引用覆盖率 × 覆盖率权重 - 无效引用扣分），范围 0.0~1.0
 * @param invalidCitations   回答里出现但不在给定来源范围内的“编造”引用编号，用于识别幻觉
 */
public record QueryResponse(
    String answer,
    Long knowledgeBaseId,
    String knowledgeBaseName,
    List<RagSourceDTO> sources,
    Double confidence,
    List<Integer> invalidCitations
) {}
