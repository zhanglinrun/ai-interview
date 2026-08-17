package com.linrun.interview.document.constant;

/**
 * 文档切块策略（对齐 know-engine {@code SplitType}，默认 TITLE）。
 */
public enum SplitType {

  /** 兄弟分段（Markdown 标题 + 超长章节二次切割）。 */
  BROTHER,

  /** 按标题切分（know-engine TITLE：1～titleLevel 级标题，超长才升格父子）。 */
  TITLE,

  /** TITLE 别名，兼容旧请求。 */
  PARENT_CHILD,

  /** 智能切块：Parent + chunkSize 10% overlap。 */
  SMART,

  /** 按字符长度切块。 */
  LENGTH,

  /** 按分隔符正则切块。 */
  SEPARATOR,

  /** 按自定义正则切块。 */
  REGEX
}
