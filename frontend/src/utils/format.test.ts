import { describe, expect, it } from 'vitest';
import { formatClockTime, formatDurationText } from './format';

describe('formatClockTime', () => {
  it('不足一小时显示分秒', () => {
    expect(formatClockTime(0)).toBe('00:00');
    expect(formatClockTime(75)).toBe('01:15');
  });

  it('超过一小时带上小时', () => {
    expect(formatClockTime(3661)).toBe('01:01:01');
  });
});

describe('formatDurationText', () => {
  it('按量级省略零段', () => {
    expect(formatDurationText(undefined)).toBe('-');
    expect(formatDurationText(45)).toBe('45秒');
    expect(formatDurationText(90)).toBe('1分30秒');
    expect(formatDurationText(3600)).toBe('1小时');
    expect(formatDurationText(3660)).toBe('1小时1分');
  });
});
