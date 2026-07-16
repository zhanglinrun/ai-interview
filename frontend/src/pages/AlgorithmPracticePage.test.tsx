import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  algorithmApi,
  type CodingAttempt,
  type CodingDraft,
  type JudgeSubmission,
} from '../api/algorithm';
import AlgorithmPracticePage from './AlgorithmPracticePage';

vi.mock('../api/algorithm', () => ({
  algorithmApi: {
    getProblem: vi.fn(),
    createAttempt: vi.fn(),
    getAttempt: vi.fn(),
    getDraft: vi.fn(),
    listSubmissions: vi.fn(),
    saveDraft: vi.fn(),
    run: vi.fn(),
    submit: vi.fn(),
  },
}));

vi.mock('../components/CodeEditor', () => ({
  default: ({ value, readOnly }: { value: string; readOnly?: boolean }) => (
    <div data-testid="code-editor" data-value={value} data-read-only={String(Boolean(readOnly))}>
      {value}
    </div>
  ),
}));

const attempt: CodingAttempt = {
  attemptId: 'attempt-1',
  problemVersionId: 11,
  mode: 'TRAINING',
  contextId: null,
  language: 'PYTHON3',
  status: 'IN_PROGRESS',
  startedAt: '2026-07-15T12:00:00',
};

const draft: CodingDraft = {
  attemptId: 'attempt-1',
  language: 'PYTHON3',
  sourceCode: 'class Solution:\n    pass',
  revision: 3,
  updatedAt: '2026-07-15T12:05:00',
};

const acceptedSubmission: JudgeSubmission = {
  submissionId: 'submission-accepted',
  attemptId: 'attempt-1',
  suiteType: 'HIDDEN',
  language: 'PYTHON3',
  status: 'ACCEPTED',
  passedCount: 3,
  totalCount: 3,
  timeMs: 50,
  memoryKb: 14064,
  pendingRejudge: false,
  submittedAt: '2026-07-15T12:06:00',
  completedAt: '2026-07-15T12:06:02',
};

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}{location.search}</div>;
}

function renderPage(entry: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path="/training/algorithm/:problemVersionId" element={(
          <>
            <AlgorithmPracticePage />
            <LocationProbe />
          </>
        )} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('AlgorithmPracticePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(algorithmApi.listSubmissions).mockResolvedValue([]);
    vi.mocked(algorithmApi.getProblem).mockResolvedValue({
      problemId: 1,
      problemVersionId: 11,
      hotRank: 1,
      platformProblemId: 'two-sum',
      title: '两数之和',
      difficulty: 'EASY',
      tags: ['数组', '哈希表'],
      sourceUrl: 'https://leetcode.cn/problems/two-sum/',
      version: 'v1',
      statement: '给定一个整数数组和目标值。',
      constraints: ['2 <= nums.length <= 10000'],
      publicExamples: [{ input: '[2,7,11,15], 9', output: '[0,1]' }],
      complexityRubric: null,
      languages: [
        { language: 'JAVA21', functionSignature: 'int[] twoSum', template: 'class Solution {}' },
        { language: 'PYTHON3', functionSignature: 'def two_sum', template: 'class Solution:' },
      ],
    });
  });

  it('使用详情响应中的 languages 渲染语言选项', async () => {
    renderPage('/training/algorithm/11');

    expect(await screen.findByRole('heading', { name: '两数之和' })).toBeInTheDocument();
    expect(screen.getByText('简单')).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Java' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Python 3' })).toBeInTheDocument();
  });

  it('从 URL 校验并恢复当前用户的 attempt 与草稿 revision', async () => {
    vi.mocked(algorithmApi.getAttempt).mockResolvedValue(attempt);
    vi.mocked(algorithmApi.getDraft).mockResolvedValue(draft);

    renderPage('/training/algorithm/11?attemptId=attempt-1');

    expect(await screen.findByText('已恢复上次保存的代码')).toBeInTheDocument();
    expect(screen.getByTestId('code-editor')).toHaveAttribute('data-value', draft.sourceCode);
    expect(screen.getByRole('combobox')).toHaveValue('PYTHON3');
    expect(algorithmApi.getAttempt).toHaveBeenCalledWith('attempt-1');
    expect(algorithmApi.getDraft).toHaveBeenCalledWith('attempt-1');
    expect(algorithmApi.listSubmissions).toHaveBeenCalledWith('attempt-1');
  });

  it('恢复已完成 attempt 的最近判题结果并切换为只读回看', async () => {
    vi.mocked(algorithmApi.getAttempt).mockResolvedValue({
      ...attempt,
      status: 'COMPLETED',
      submittedAt: acceptedSubmission.submittedAt,
      completedAt: acceptedSubmission.completedAt,
    });
    vi.mocked(algorithmApi.getDraft).mockResolvedValue(draft);
    vi.mocked(algorithmApi.listSubmissions).mockResolvedValue([acceptedSubmission]);

    renderPage('/training/algorithm/11?attemptId=attempt-1');

    expect(await screen.findByText('已恢复代码和最近一次判题结果')).toBeInTheDocument();
    expect(screen.getByText((_, element) => (
      element?.tagName === 'P' && element.textContent?.includes('通过 3/3') === true
    ))).toBeInTheDocument();
    expect(screen.getByText('本次作答已完成，代码和判题结果仅供回看。')).toBeInTheDocument();
    expect(screen.getByTestId('code-editor')).toHaveAttribute('data-read-only', 'true');
    expect(screen.queryByRole('button', { name: '保存草稿' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '正式提交' })).not.toBeInTheDocument();
  });

  it('创建作答后把 attemptId 写入 URL 并走恢复链路', async () => {
    vi.mocked(algorithmApi.createAttempt).mockResolvedValue(attempt);
    vi.mocked(algorithmApi.getAttempt).mockResolvedValue(attempt);
    vi.mocked(algorithmApi.getDraft).mockResolvedValue(draft);
    renderPage('/training/algorithm/11');

    fireEvent.click(await screen.findByRole('button', { name: '开始作答' }));

    await waitFor(() => expect(screen.getByTestId('location'))
      .toHaveTextContent('/training/algorithm/11?attemptId=attempt-1'));
    expect(await screen.findByText('已恢复上次保存的代码')).toBeInTheDocument();
    expect(algorithmApi.createAttempt).toHaveBeenCalledWith(11, 'JAVA21');
  });

  it('拒绝恢复属于其他题目的 attempt', async () => {
    vi.mocked(algorithmApi.getAttempt).mockResolvedValue({
      ...attempt,
      problemVersionId: 12,
    });

    renderPage('/training/algorithm/11?attemptId=attempt-1');

    expect(await screen.findByText('该作答记录不属于当前算法题')).toBeInTheDocument();
    expect(algorithmApi.getDraft).not.toHaveBeenCalled();
  });

  it('判题服务不可用时不展示误导性的 0/N 通过数', async () => {
    vi.mocked(algorithmApi.getAttempt).mockResolvedValue(attempt);
    vi.mocked(algorithmApi.getDraft).mockResolvedValue(draft);
    vi.mocked(algorithmApi.saveDraft).mockResolvedValue({ ...draft, revision: 4 });
    vi.mocked(algorithmApi.run).mockResolvedValue({
      submissionId: 'submission-1',
      attemptId: attempt.attemptId,
      suiteType: 'PUBLIC',
      language: 'PYTHON3',
      status: 'UNAVAILABLE',
      passedCount: 0,
      totalCount: 3,
      pendingRejudge: true,
      submittedAt: '2026-07-15T12:06:00',
    });
    renderPage('/training/algorithm/11?attemptId=attempt-1');

    fireEvent.click(await screen.findByRole('button', { name: '运行公开用例' }));

    expect(await screen.findByText('未执行判题，代码已保存')).toBeInTheDocument();
    expect(screen.queryByText('通过 0/3')).not.toBeInTheDocument();
  });
});
