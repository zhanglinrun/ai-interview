import {describe, expect, it} from 'vitest';
import {getInterviewViewPath} from './interviewNavigation';

describe('getInterviewViewPath', () => {
  it('打开文字面试详情页', () => {
    expect(getInterviewViewPath('session/1')).toBe('/interviews/session%2F1');
  });
});
