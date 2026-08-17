package com.linrun.interview.rag.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * RAG 评测 Dataset 生成请求。
 */
public record DatasetGenerateRequest(
    @NotBlank String question,
    @NotEmpty List<Long> knowledgeBaseIds
) {
}
