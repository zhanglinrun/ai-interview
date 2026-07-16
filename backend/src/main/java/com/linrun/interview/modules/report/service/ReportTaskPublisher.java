package com.linrun.interview.modules.report.service;

public interface ReportTaskPublisher {
  void publish(String reportId, Long userId);
}
