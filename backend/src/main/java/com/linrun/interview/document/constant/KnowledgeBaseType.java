package com.linrun.interview.document.constant;

/**
 * 知识库类型。
 */
public enum KnowledgeBaseType {

 /** 文档语义检索：切块 + 向量化。Excel/CSV 按行切，不建宽表。 */
 DOCUMENT_SEARCH,

 /** 数据查询：Excel/CSV 导入 MySQL 动态表，走 Text2SQL，不切块、不向量化。 */
 DATA_QUERY
}
