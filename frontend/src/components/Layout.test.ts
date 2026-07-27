import { describe, expect, it } from 'vitest';
import { resolveDocumentTitle } from './Layout';

describe('Layout document title', () => {
  it('面试日程不会被模拟面试的前缀路由覆盖', () => {
    expect(resolveDocumentTitle('/interview-schedule'))
      .toBe('面试日程 · AI 面试平台');
  });

  it('知识库、Agent Trace 与评测有独立标题，不被我的资料覆盖', () => {
    expect(resolveDocumentTitle('/knowledgebase')).toBe('知识库 · AI 面试平台');
    expect(resolveDocumentTitle('/knowledgebase/chat')).toBe('问答助手 · AI 面试平台');
    expect(resolveDocumentTitle('/agent-trace')).toBe('Agent 编排 Trace · AI 面试平台');
    expect(resolveDocumentTitle('/eval')).toBe('RAG 效果评测 · AI 面试平台');
  });
});
