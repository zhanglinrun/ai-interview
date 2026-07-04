package com.linrun.interview.modules.interview.agent.model;

import java.util.List;

/**
 * Planner Agent 产出的结构化面试大纲。
 *
 * @param topics          考察主题节点（按出题顺序），questionCount 之和应等于总题数
 * @param difficultyCurve 难度曲线描述（如「由浅入深，最后两题聚焦架构设计」）
 * @param focusFromResume 从简历提取的重点考察方向
 * @param focusFromJd     从 JD/Skill 提取的重点考察方向
 */
public record InterviewPlan(
    List<PlanTopic> topics,
    String difficultyCurve,
    List<String> focusFromResume,
    List<String> focusFromJd
) {

  /**
   * 大纲主题节点。
   *
   * @param name          主题名（如「MySQL 索引与事务」）
   * @param focus         该主题的考察重点（一句话）
   * @param questionCount 计划出题数
   */
  public record PlanTopic(String name, String focus, int questionCount) {}

  /** 按线性题号定位当前大纲节点（超出计划时停留在最后一个节点）。 */
  public PlanTopic topicForQuestion(int questionIndex) {
    if (topics == null || topics.isEmpty()) {
      return null;
    }
    int cursor = 0;
    for (PlanTopic topic : topics) {
      cursor += Math.max(1, topic.questionCount());
      if (questionIndex < cursor) {
        return topic;
      }
    }
    return topics.get(topics.size() - 1);
  }
}
