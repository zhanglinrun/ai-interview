package com.linrun.interview.modules.jobinterview.service;

/**
 * 岗位实战完成后的异步边界。实现方可投递报告任务；ABORTED 不调用此端口。
 */
public interface JobInterviewCompletionPublisher {

  void publishCompleted(String sessionId, Long userId);
}
