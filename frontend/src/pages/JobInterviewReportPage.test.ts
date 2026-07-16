import { describe, expect, it } from 'vitest';
import { evidenceSourceLabel, objectiveAnswerText } from './JobInterviewReportPage';

describe('JobInterviewReportPage evidence labels', () => {
  it('将内部证据标识映射成用户可理解的来源类型', () => {
    expect(evidenceSourceLabel('job:1:POSITION_TECH:1.0.0')).toBe('目标岗位要求');
    expect(evidenceSourceLabel('platform-opentelemetry-summary-v1')).toBe('平台面试资料');
    expect(evidenceSourceLabel('chunk:42')).toBe('个人知识库');
  });
});

describe('JobInterviewReportPage answer display', () => {
  it('不把内部源码哈希当成用户可读的代码内容', () => {
    expect(objectiveAnswerText({
      stage: 'ALGORITHM',
      answer: '[代码提交] sha256=947a38b9618cc0f93910610aa79702f08354e8e12286ac087e03afb14d35f9db',
    })).toBe('代码已提交；报告仅保留提交摘要，源码未在复盘中重复展示。');
    expect(objectiveAnswerText({ stage: 'POSITION_TECH', answer: '正常回答' }))
      .toBe('正常回答');
  });
});
