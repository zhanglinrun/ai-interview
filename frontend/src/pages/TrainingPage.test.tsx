import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { algorithmApi } from '../api/algorithm';
import TrainingPage from './TrainingPage';

vi.mock('../api/algorithm', () => ({
  algorithmApi: {
    listProblems: vi.fn(),
  },
}));

vi.mock('../api/report', () => ({
  reportApi: {
    listTrainingTasks: vi.fn().mockResolvedValue([]),
  },
}));

describe('TrainingPage', () => {
  beforeEach(() => {
    vi.mocked(algorithmApi.listProblems).mockResolvedValue([
      {
        problemId: 1,
        problemVersionId: 11,
        hotRank: 1,
        platformProblemId: 'two-sum',
        title: '两数之和',
        difficulty: 'EASY',
        tags: ['ARRAY', 'HASH_TABLE'],
        sourceUrl: 'https://leetcode.cn/problems/two-sum/',
        version: 'v1',
        enabledLanguages: ['JAVA21', 'PYTHON3'],
      },
    ]);
  });

  it('从真实 API 结果渲染可进入的算法题，不内置假题', async () => {
    render(<MemoryRouter><TrainingPage /></MemoryRouter>);

    const link = await screen.findByRole('link', { name: /两数之和/ });
    expect(link).toHaveAttribute('href', '/training/algorithm/11');
    expect(screen.getByText('Java / Python 3')).toBeInTheDocument();
    expect(screen.getByText('数组')).toBeInTheDocument();
    expect(screen.getByText('哈希表')).toBeInTheDocument();
    expect(screen.queryByText('HASH_TABLE')).not.toBeInTheDocument();
  });

  it('完整目录中的未接入题目跳转到力扣官方题面', async () => {
    vi.mocked(algorithmApi.listProblems).mockResolvedValue([
      {
        problemId: 1,
        problemVersionId: 11,
        hotRank: 1,
        platformProblemId: 'two-sum',
        title: '两数之和',
        difficulty: 'EASY',
        tags: ['ARRAY'],
        sourceUrl: 'https://leetcode.cn/problems/two-sum/',
        version: 'v1',
        enabledLanguages: ['JAVA21', 'PYTHON3'],
      },
      {
        problemId: 2,
        problemVersionId: null,
        hotRank: 100,
        platformProblemId: 'find-the-duplicate-number',
        title: '寻找重复数',
        difficulty: 'MEDIUM',
        tags: ['ARRAY'],
        sourceUrl: 'https://leetcode.cn/problems/find-the-duplicate-number/',
        version: null,
        enabledLanguages: [],
      },
    ]);

    render(<MemoryRouter><TrainingPage /></MemoryRouter>);

    const link = await screen.findByRole('link', { name: /寻找重复数（打开力扣题面）/ });
    expect(link).toHaveAttribute('href', 'https://leetcode.cn/problems/find-the-duplicate-number/');
    expect(link).toHaveAttribute('target', '_blank');
    expect(screen.getByText('当前收录 2 道题，其中 1 道支持平台内在线作答，其余可打开 LeetCode 官方题面。')).toBeInTheDocument();
  });
});
