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
});
