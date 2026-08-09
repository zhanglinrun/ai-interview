package com.linrun.interview.business.service;

public interface ReportTaskPublisher {
  void publish(String reportId, Long userId);
}
