package com.linrun.interview.modules.knowledgebase.constant;

/**
 * 知识库类型（对齐 know-engine {@code KnowledgeBaseType}）。
 */
public enum KnowledgeBaseType {

  /** 语义检索型：切块 + 向量化。 */
  DOCUMENT_SEARCH,

  /** 数据查询型：动态表 + Text2SQL，不向量化。 */
  DATA_QUERY
}
