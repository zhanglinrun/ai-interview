import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { historyApi } from '../api/history';
import { knowledgeBaseApi, type KnowledgeBaseItem } from '../api/knowledgebase';
import InterviewSetupPage, { canStartInterview, groupKnowledgeBasesByCategory } from './InterviewSetupPage';

function knowledgeBase(partial: Pick<KnowledgeBaseItem, 'id' | 'name' | 'category'>): KnowledgeBaseItem {
  return {
    originalFilename: `${partial.name}.md`,
    fileSize: 10,
    contentType: 'text/markdown',
    uploadedAt: '2026-08-16T10:00:00',
    lastAccessedAt: '2026-08-16T10:00:00',
    accessCount: 0,
    questionCount: 0,
    docStatus: 'VECTOR_STORED',
    currentVersionId: 1,
    accessibleBy: 'PRIVATE',
    expireDate: null,
    owned: true,
    ...partial,
  };
}

vi.mock('../api/history', () => ({
  historyApi: {
    getResumes: vi.fn(),
    getResumeDetail: vi.fn(),
  },
}));

vi.mock('../api/knowledgebase', () => ({
  knowledgeBaseApi: {
    getAllKnowledgeBases: vi.fn(),
  },
}));

describe('canStartInterview', () => {
  it('JD 满 50 字或已选简历才能开场', () => {
    expect(canStartInterview('太短', undefined)).toBe(false);
    expect(canStartInterview('负责 Java 后端开发与检索链路'.repeat(4), undefined)).toBe(true);
    expect(canStartInterview('', 12)).toBe(true);
  });
});

describe('InterviewSetupPage', () => {
  beforeEach(() => {
    vi.mocked(historyApi.getResumes).mockResolvedValue([
      {
        id: 12,
        filename: 'zhangsan.pdf',
        fileSize: 1024,
        uploadedAt: '2026-08-16T10:00:00',
        accessCount: 0,
        interviewCount: 0,
      },
    ]);
    vi.mocked(knowledgeBaseApi.getAllKnowledgeBases).mockResolvedValue([
      knowledgeBase({ id: 3, name: 'RAG 笔记', category: '检索' }),
      knowledgeBase({ id: 4, name: 'RocketMQ', category: '后端八股' }),
      knowledgeBase({ id: 5, name: 'Redis', category: '后端八股' }),
    ]);
  });

  it('展示 JD、简历和知识库准备项，未选材料时不能开场', async () => {
    render(
      <MemoryRouter>
        <InterviewSetupPage onStart={vi.fn()} />
      </MemoryRouter>,
    );

    expect(await screen.findByText('目标岗位 JD')).toBeInTheDocument();
    expect(screen.getByText('zhangsan.pdf')).toBeInTheDocument();
    expect(screen.getByText('RAG 笔记')).toBeInTheDocument();
    expect(screen.getByText('面试方向')).toBeInTheDocument();
    expect(screen.getByText('题数')).toBeInTheDocument();
    expect(screen.queryByText('难度')).not.toBeInTheDocument();
    expect(screen.queryByText('初级')).not.toBeInTheDocument();
    expect(screen.queryByText('中级')).not.toBeInTheDocument();
    expect(screen.queryByText('高级')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '开始面试' })).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: '全选 后端八股' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '全选 检索' })).toBeInTheDocument();
  });

  it('勾选分类会选中该类下全部资料', async () => {
    const onStart = vi.fn();
    render(
      <MemoryRouter>
        <InterviewSetupPage onStart={onStart} />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole('checkbox', { name: '全选 后端八股' }));
    fireEvent.change(screen.getByPlaceholderText('粘贴岗位职责、任职要求和加分项…'), {
      target: { value: '负责 Java 后端开发与检索链路'.repeat(4) },
    });
    fireEvent.click(screen.getByRole('button', { name: '开始面试' }));

    expect(onStart).toHaveBeenCalledWith(expect.objectContaining({
      skillId: 'java-backend',
      questionCount: 8,
      knowledgeBaseIds: [4, 5],
    }));
  });
});

describe('groupKnowledgeBasesByCategory', () => {
  it('按分类聚合，未分类放最后', () => {
    const groups = groupKnowledgeBasesByCategory([
      knowledgeBase({ id: 1, name: '未命名', category: null }),
      knowledgeBase({ id: 2, name: 'Redis', category: '后端八股' }),
      knowledgeBase({ id: 3, name: 'JVM', category: '后端八股' }),
    ]);

    expect(groups.map((group) => group.category)).toEqual(['后端八股', '未分类']);
    expect(groups[0].items.map((item) => item.id)).toEqual([2, 3]);
  });
});
