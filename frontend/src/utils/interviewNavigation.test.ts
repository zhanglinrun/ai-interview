import {describe, expect, it} from 'vitest';
import {getInterviewViewPath} from './interviewNavigation';

describe('getInterviewViewPath', () => {
  it.each(['COMPLETED', 'EVALUATED'])(
    '岗位实战终态 %s 打开报告页',
    (status) => {
      expect(getInterviewViewPath('session-1', true, status))
        .toBe('/job-practice/report/session-1');
    },
  );

  it('未完成的岗位实战打开作答页', () => {
    expect(getInterviewViewPath('session-1', true, 'IN_PROGRESS'))
      .toBe('/job-practice/session/session-1');
  });

  it('普通面试仍打开原有详情页', () => {
    expect(getInterviewViewPath('session/1', false, 'EVALUATED'))
      .toBe('/interviews/session%2F1');
  });
});
