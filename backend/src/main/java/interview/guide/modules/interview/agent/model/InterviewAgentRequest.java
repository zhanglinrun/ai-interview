package interview.guide.modules.interview.agent.model;

import java.util.List;

/**
 * 面试官 Agent 出题请求。
 *
 * @param skillId          面试方向（如 java-backend），可为 null 走通用
 * @param difficulty       难度（junior / mid / senior），可为 null 默认 mid
 * @param resumeId         候选人简历ID，可为 null 表示无简历通用面试
 * @param knowledgeBaseIds 关联的岗位知识库ID，可为空
 * @param llmProvider      指定的 LLM 提供商，可为 null 用默认
 * @param conversationLog  已进行的问答摘要（面试官视角），首题可为空
 */
public record InterviewAgentRequest(
    String skillId,
    String difficulty,
    Long resumeId,
    List<Long> knowledgeBaseIds,
    String llmProvider,
    String conversationLog
) {}
