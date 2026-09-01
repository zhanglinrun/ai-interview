import { describe, expect, it } from 'vitest';
import {
  getInterviewStatusText,
  hasReliableEvaluationScore,
  isDegradedEvaluationFeedback,
  isEvaluationFailed,
} from './interviewStatus';

describe('interviewStatus', () => {
  it('treats fallback copy as degraded', () => {
    expect(isDegradedEvaluationFeedback('该题未成功生成评估结果，系统按 0 分处理。')).toBe(true);
    expect(isDegradedEvaluationFeedback('能说明失败窗口与工程取舍。')).toBe(false);
  });

  it('does not treat failed or degraded zero as a reliable score', () => {
    expect(isEvaluationFailed('FAILED')).toBe(true);
    expect(hasReliableEvaluationScore({
      evaluateStatus: 'COMPLETED',
      status: 'EVALUATED',
      overallScore: 0,
      evaluationDegraded: true,
    })).toBe(false);
    expect(hasReliableEvaluationScore({
      evaluateStatus: 'FAILED',
      status: 'COMPLETED',
      overallScore: 0,
    })).toBe(false);
    expect(hasReliableEvaluationScore({
      evaluateStatus: 'COMPLETED',
      status: 'EVALUATED',
      overallScore: 80,
    })).toBe(true);
  });

  it('maps a ready session as 待开始 instead of 已创建', () => {
    expect(getInterviewStatusText('READY', null)).toBe('待开始');
    expect(getInterviewStatusText('CREATED', null)).toBe('已创建');
  });
});
