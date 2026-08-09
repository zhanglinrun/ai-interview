package com.linrun.interview.document.service;

/**
 * Embedding provider 的安全批次上限。
 *
 * <p>统一证据索引与知识库索引必须使用同一上限，避免某一条链路一次请求过大而整批失败。
 */
public final class EmbeddingBatchPolicy {

  public static final int MAX_BATCH_SIZE = 10;

  private EmbeddingBatchPolicy() {
  }
}
