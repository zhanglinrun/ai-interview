import { describe, expect, it } from 'vitest';
import {
  buildRagCardFollowUp,
  categoriesForSelectedKnowledgeBases,
  citationStatusLabel,
  cleanSourceSnippet,
  collectKnowledgeBaseCategories,
  filterQueryableKnowledgeBases,
  groupRagSourcesByDocument,
  groundedStatusDisplay,
  groundedStatusLabel,
  knowledgeBaseCategoryName,
  mergeCategoryNames,
  removeQuestionSearchParam,
  shouldShowQueryChatPane,
  stripPersistedSourceAppendix,
} from './KnowledgeBaseQueryPage';

describe('KnowledgeBaseQueryPage card follow-up', () => {
  it('将 jobTrack 选项映射为带岗位方向 ID 的追问', () => {
    expect(buildRagCardFollowUp({
      id: 'java-backend',
      label: 'Java 后端',
      type: 'jobTrack',
    })).toBe('请针对「Java 后端」方向（jobTrack=java-backend）给出面试准备建议');
  });

  it('简历选项追问携带简历 ID，便于下一轮实体抽取', () => {
    expect(buildRagCardFollowUp({
      id: '12',
      label: '张三-阿里.pdf',
      type: 'resume',
    })).toBe('请分析简历 ID=12（张三-阿里.pdf）');
  });

  it('会话选项追问携带会话 ID', () => {
    expect(buildRagCardFollowUp({
      id: 'abc123def456',
      label: 'java · 88分',
      type: 'session',
    })).toBe('请总结这场面试，会话 ID=abc123def456（java · 88分）');
  });

  it('日程选项追问携带安排 ID', () => {
    expect(buildRagCardFollowUp({
      id: '9',
      label: '字节 · 后端',
      type: 'schedule',
    })).toBe('请查询面试安排 ID=9（字节 · 后端）');
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

  it('grounded 状态在问答页用更短的中文标签', () => {
    expect(groundedStatusDisplay(null)).toBeNull();
    expect(groundedStatusDisplay('pass')).toEqual({ label: '依据充分', warn: false });
    expect(groundedStatusDisplay('grounded')).toEqual({ label: '依据充分', warn: false });
    expect(groundedStatusDisplay('need_escalate')).toEqual({ label: '依据不足', warn: true });
    expect(groundedStatusDisplay('other')).toEqual({ label: 'grounded: other', warn: true });
  });
});

describe('KnowledgeBaseQueryPage queryable materials', () => {
  it('搜索结果只保留向量化完成的知识库', () => {
    expect(filterQueryableKnowledgeBases([
      { id: 1, docStatus: 'VECTOR_STORED' },
      { id: 2, docStatus: 'CHUNKED' },
      { id: 3, docStatus: 'CONVERTING' },
      { id: 4, docStatus: 'STORED' },
    ])).toEqual([{ id: 1, docStatus: 'VECTOR_STORED' }]);
  });

  it('空分类归到未分类，并收集当前列表里的全部分类', () => {
    expect(knowledgeBaseCategoryName(null)).toBe('未分类');
    expect(knowledgeBaseCategoryName('  ')).toBe('未分类');
    expect(collectKnowledgeBaseCategories([
      { category: '后端八股' },
      { category: null },
      { category: '后端八股' },
    ])).toEqual(['后端八股', '未分类']);
  });

  it('打开会话时展开已绑定资料所在分类', () => {
    expect(categoriesForSelectedKnowledgeBases([
      { id: 1, category: '后端八股' },
      { id: 2, category: '力扣题' },
      { id: 3, category: '后端八股' },
    ], [2, 3])).toEqual(['力扣题', '后端八股']);
  });

  it('合并分类时保留用户已展开的项', () => {
    expect([...mergeCategoryNames(['未分类'], ['后端八股', '未分类'])].sort())
      .toEqual(['后端八股', '未分类']);
  });

  it('已打开历史对话时即使没勾选资料也显示问答区', () => {
    expect(shouldShowQueryChatPane(0, null)).toBe(false);
    expect(shouldShowQueryChatPane(1, null)).toBe(true);
    expect(shouldShowQueryChatPane(0, 12)).toBe(true);
  });
});

describe('KnowledgeBaseQueryPage source display', () => {
  it('剥掉历史回答里拼进去的参考来源附录', () => {
    const content = [
      'Redis 基于内存，所以快。',
      '',
      '---',
      '',
      '## 参考来源',
      '',
      '1. **面渣逆袭Redis篇V2.0.pdf**（相关度 2.67）',
      '',
      '   > ![](http://localhost:29000/ai-interview/converted/49/images/a.png) Redis 为什么快',
    ].join('\n');

    expect(stripPersistedSourceAppendix(content)).toBe('Redis 基于内存，所以快。');
  });

  it('清洗片段中的图片链接和裸 URL', () => {
    expect(cleanSourceSnippet(
      '## 为什么快 ![](http://localhost:29000/ai-interview/converted/49/images/a.png) 因为基于内存',
    )).toBe('为什么快 因为基于内存');
  });

  it('同一文档的多个片段归到一组，并保留原始编号', () => {
    const groups = groupRagSourcesByDocument([
      {
        knowledgeBaseId: 1,
        documentTitle: '面渣逆袭Redis篇V2.0.pdf',
        sourceName: '面渣逆袭Redis篇V2.0.pdf',
        category: null,
        sectionTitle: null,
        chunkIndex: null,
        chunkCount: null,
        snippet: '片段一',
        similarity: 2.67,
        cited: true,
      },
      {
        knowledgeBaseId: 1,
        documentTitle: '面渣逆袭Redis篇V2.0.pdf',
        sourceName: '面渣逆袭Redis篇V2.0.pdf',
        category: null,
        sectionTitle: null,
        chunkIndex: null,
        chunkCount: null,
        snippet: '片段二',
        similarity: 0.8,
        cited: false,
      },
    ]);

    expect(groups).toHaveLength(1);
    expect(groups[0].title).toBe('面渣逆袭Redis篇V2.0.pdf');
    expect(groups[0].items.map((item) => item.index)).toEqual([0, 1]);
  });
});
