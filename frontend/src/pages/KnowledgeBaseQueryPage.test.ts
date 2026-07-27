import { describe, expect, it } from 'vitest';
import {
  buildRagCardFollowUp,
  citationStatusLabel,
  groundedStatusLabel,
  removeQuestionSearchParam,
} from './KnowledgeBaseQueryPage';

describe('KnowledgeBaseQueryPage card follow-up', () => {
  it('将 jobTrack 选项映射为岗位方向提问，而不是简历分析', () => {
    expect(buildRagCardFollowUp({
      id: 'java-backend',
      label: 'Java 后端',
      type: 'jobTrack',
    })).toBe('请针对「Java 后端」方向给出面试准备建议');
  });

  it('发送训练页预填问题后只清理 question 参数', () => {
    const result = removeQuestionSearchParam(
      new URLSearchParams('question=RAG%20链路&task=training-1'),
    );

    expect(result.get('question')).toBeNull();
    expect(result.get('task')).toBe('training-1');
  });

  it('citation 终态到达后区分已引用和未引用来源', () => {
    const source = {
      knowledgeBaseId: 1,
      documentTitle: 'RAG.md',
      sourceName: 'RAG.md',
      category: null,
      sectionTitle: null,
      chunkIndex: null,
      chunkCount: null,
      snippet: '片段',
      similarity: 0.9,
      cited: true,
    };

    expect(citationStatusLabel(source, false)).toBeNull();
    expect(citationStatusLabel(source, true)).toBe('已引用');
    expect(citationStatusLabel({ ...source, cited: false }, true)).toBe('未引用');
  });

  it('grounded 闸门状态展示 pass / grounded / need_escalate', () => {
    expect(groundedStatusLabel(null)).toBeNull();
    expect(groundedStatusLabel('pass')).toBe('grounded: pass');
    expect(groundedStatusLabel('grounded')).toBe('grounded: grounded');
    expect(groundedStatusLabel('need_escalate')).toBe('grounded: need_escalate');
    expect(groundedStatusLabel('other')).toBe('grounded: other');
  });
});
