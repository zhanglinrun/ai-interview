package com.linrun.interview.rag.model;

import java.util.List;

/**
 * RAG 评测 Dataset 单题结果。
 *
 * <p>{@code references} 是同一次 augment 喂给生成模型的完整 chunk 文本，
 * 不是截断 snippet，也不是另一次检索。
 */
public record DatasetGenerateResult(
    String question,
    String answer,
    List<String> references
) {
}
