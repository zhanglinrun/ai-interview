package com.linrun.interview.modules.interview.agent.tool;

import java.util.List;

/**
 * 面试官 Agent 单轮运行的只读上下文。
 *
 * @param skillId          面试技能方向（如 java-backend）
 * @param difficulty       难度（junior / mid / senior）
 * @param resumeId         候选人简历ID（可为 null，表示无简历通用面试）
 * @param knowledgeBaseIds 可检索的岗位知识库ID（可为空）
 */
public record AgentToolContext(
    String skillId,
    String difficulty,
    Long resumeId,
    List<Long> knowledgeBaseIds
) {
    public AgentToolContext {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
    }
}
