import { afterEach, describe, expect, it } from 'vitest';
import { resolveInterviewStartedAt } from './interviewTimer';

describe('resolveInterviewStartedAt', () => {
  afterEach(() => {
    sessionStorage.clear();
  });

  it('优先使用会话创建时间', () => {
    expect(resolveInterviewStartedAt('s1', '2026-08-16T09:00:00')).toBe('2026-08-16T09:00:00');
  });

  it('没有创建时间时同一场次复用本地起点', () => {
    const first = resolveInterviewStartedAt('s2');
    const second = resolveInterviewStartedAt('s2');
    expect(second).toBe(first);
  });
});
