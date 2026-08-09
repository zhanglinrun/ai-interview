package com.linrun.interview.business.service;

import com.linrun.interview.business.vo.InterviewReportDTO;

/** 面试报告生成端口，报告编排不泄漏给 HTTP 层。 */
public interface InterviewReportPort {
    InterviewReportDTO generateReport(String sessionId);
}
