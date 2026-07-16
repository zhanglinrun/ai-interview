import { describe, expect, it } from 'vitest';
import { buildRagCardFollowUp, removeQuestionSearchParam } from './KnowledgeBaseQueryPage';

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
});
