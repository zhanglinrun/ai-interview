package com.linrun.interview.github.model;

/** 代码感知切块结果；行号均为 1-based 且包含首尾。 */
public record CodeChunk(
    String symbolName,
    String symbolKind,
    int startLine,
    int endLine,
    String content
) {
}
