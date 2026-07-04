package com.linrun.interview.modules.knowledgebase.constant;

/**
 * 文档切块策略（对齐业界实践 {@code SplitType}，额外保留 BROTHER 作为本项目默认）。
 */
public enum SplitType {

  /** 兄弟分段（Markdown 标题 + 超长章节二次切割，默认）。 */
  BROTHER,

  /** 按标题层级切块（Parent-Child）。 */
  TITLE,

  /** 智能切块：Parent + chunkSize 10% overlap。 */
  SMART,

  /** 按字符长度切块。 */
  LENGTH,

  /** 按分隔符正则切块。 */
  SEPARATOR,

  /** 按自定义正则切块。 */
  REGEX
}
