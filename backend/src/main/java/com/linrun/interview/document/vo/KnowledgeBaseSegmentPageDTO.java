package com.linrun.interview.document.vo;

import java.util.List;

/**
 * 分段分页结果。
 */
public record KnowledgeBaseSegmentPageDTO(
    long total,
    long current,
    long size,
    List<KnowledgeBaseSegmentDTO> records
) {
}
