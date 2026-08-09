package com.linrun.interview.business.service;

import java.util.List;

/**
 * 面试运行时使用的中性主题视图。
 *
 * <p>预设主题由已发布的版本化能力模板投影而来；自定义主题只承载一次面试冻结的 JD
 * 能力范围，不包含 Persona、工具说明或任意 Markdown 指令。
 */
public record InterviewTopic(
    String id,
    String name,
    String description,
    List<Category> categories,
    boolean preset,
    String sourceJd,
    String templateCode,
    String templateVersion
) {

  public InterviewTopic {
    categories = categories == null ? List.of() : List.copyOf(categories);
  }

  /** 能力模板中的一个可分配考察分类。 */
  public record Category(
      String key,
      String label,
      String priority,
      String definitionVersion
  ) {
  }
}
