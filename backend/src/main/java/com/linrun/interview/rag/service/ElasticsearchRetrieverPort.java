package com.linrun.interview.rag.service;

import dev.langchain4j.rag.content.retriever.ContentRetriever;

/** ES 混合向量/BM25 检索端口；具体索引客户端留在 rag 适配层。 */
public interface ElasticsearchRetrieverPort extends ContentRetriever {
}
