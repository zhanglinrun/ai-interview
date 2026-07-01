package com.linrun.interview.modules.knowledgebase.model;

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
