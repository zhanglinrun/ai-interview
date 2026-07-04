package com.linrun.interview.modules.knowledgebase.model;

import java.util.List;

/**
 * 离线 Dataset 生成结果（对齐业界实践 DatasetController.DatasetResult）。
 */
public record RagDatasetResult(
    String question,
    String answer,
    List<RagSourceDTO> sources,
    String rewrittenQuestion,
    String routeStrategy,
    long latencyMs
) {
}
