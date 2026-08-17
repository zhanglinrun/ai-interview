import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useElapsedSeconds } from './useElapsedSeconds';

describe('useElapsedSeconds', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('按秒刷新已用时', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-16T10:00:10Z'));
    const { result } = renderHook(() => useElapsedSeconds('2026-08-16T10:00:00Z'));
    expect(result.current).toBe(10);

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current).toBe(11);
  });
});
