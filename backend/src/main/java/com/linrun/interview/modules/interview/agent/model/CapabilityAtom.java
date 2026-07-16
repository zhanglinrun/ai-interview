package com.linrun.interview.modules.interview.agent.model;

/**
 * 可跨轮次追踪的最小面试能力单元。
 *
 * @param id          稳定标识；能力模板、JD 主题和大纲主题均可跨会话聚合
 * @param label       面向用户展示的能力名称
 * @param description 本轮考察重点
 * @param source      能力来源
 * @param priority    能力模板中的优先级；无配置时为空
 * @param definitionVersion 版本化能力定义版本；历史兼容路径为空
 */
public record CapabilityAtom(
    String id,
    String label,
    String description,
    Source source,
    String priority,
    String definitionVersion
) {

  /** 历史兼容构造器；新岗位快照应显式传 definitionVersion。 */
  public CapabilityAtom(
      String id,
      String label,
      String description,
      Source source,
      String priority
  ) {
    this(id, label, description, source, priority, null);
  }

  public enum Source {
    TEMPLATE,
    JD,
    USER_MATERIAL,
    PLAN
  }
}
