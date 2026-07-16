import { describe, expect, it } from 'vitest';
import { resolveDocumentTitle } from './Layout';

describe('Layout document title', () => {
  it('面试日程不会被模拟面试的前缀路由覆盖', () => {
    expect(resolveDocumentTitle('/interview-schedule'))
      .toBe('面试日程 · AI 面试平台');
  });
});
