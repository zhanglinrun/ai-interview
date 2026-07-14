package com.linrun.interview.modules.interview.agent.model;

/**
 * 可跨轮次追踪的最小面试能力单元。
 *
 * @param id          稳定标识；预设 Skill、JD 主题和大纲主题均可跨会话聚合
 * @param label       面向用户展示的能力名称
 * @param description 本轮考察重点
 * @param source      能力来源
 * @param priority    Skill 中的优先级；无配置时为空
 */
public record CapabilityAtom(
    String id,
    String label,
    String description,
    Source source,
    String priority
) {

  public enum Source {
    SKILL,
    JD,
    USER_MATERIAL,
    PLAN
  }
}
