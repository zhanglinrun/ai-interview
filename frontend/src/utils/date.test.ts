import { afterEach, describe, expect, it, vi } from 'vitest';
import { getDurationSeconds, getElapsedSecondsSince } from './date';

describe('getDurationSeconds', () => {
  it('计算起止间隔，非法区间返回空', () => {
    expect(getDurationSeconds('2026-08-16T09:00:00', '2026-08-16T10:00:00')).toBe(3600);
    expect(getDurationSeconds('2026-08-16T10:00:00', '2026-08-16T09:00:00')).toBeUndefined();
    expect(getDurationSeconds('', '2026-08-16T10:00:00')).toBeUndefined();
  });
});

describe('getElapsedSecondsSince', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('从起点累计到当前时间', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-16T10:00:10Z'));
    expect(getElapsedSecondsSince('2026-08-16T10:00:00Z')).toBe(10);
  });
});
