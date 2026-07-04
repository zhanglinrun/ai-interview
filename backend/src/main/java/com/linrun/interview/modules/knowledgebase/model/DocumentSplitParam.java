package com.linrun.interview.modules.knowledgebase.model;

/**
 * 文档切块参数（对齐业界实践 {@code DocumentSplitParam}）。
 */
public record DocumentSplitParam(
    String splitType,
    Integer chunkSize,
    Integer overlap,
    Integer titleLevel,
    String separator,
    String regex
) {
}
