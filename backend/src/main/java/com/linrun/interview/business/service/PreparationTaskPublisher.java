package com.linrun.interview.business.service;

/** RabbitMQ 发布边界，便于状态机测试替换为内存实现。 */
public interface PreparationTaskPublisher {
  void publish(String runId, Long userId);
}
