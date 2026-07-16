import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  jobInterviewApi,
  subscribeJobInterviewEvents,
  type JobInterviewSession,
} from '../api/jobInterview';
import JobInterviewRuntimePage from './JobInterviewRuntimePage';

vi.mock('../api/jobInterview', () => ({
  jobInterviewApi: {
    getSession: vi.fn(),
    continue: vi.fn(),
    finish: vi.fn(),
    abort: vi.fn(),
    getCodeDraft: vi.fn(),
  },
  subscribeJobInterviewEvents: vi.fn(() => () => undefined),
}));

vi.mock('../components/CodeEditor', () => ({
  default: () => <div data-testid="code-editor" />,
}));

const pausedSession: JobInterviewSession = {
  sessionId: 'session-1',
  status: 'PAUSED',
  sessionVersion: 4,
  stage: 'POSITION_TECH',
  jobDescriptionId: 1,
  jobDescriptionVersion: 1,
  capabilityTemplateCode: 'java-backend-v1',
  capabilityTemplateVersion: '1.0.0',
  planVersion: 'plan-v1',
  promptVersion: 'prompt-v1',
  codingLanguage: 'JAVA21',
  personalKnowledgeEnabled: false,
  degradedReasons: [],
  currentQuestion: null,
  answeredQuestions: 2,
  totalQuestions: 6,
  resumeExpiresAt: '2026-07-17T12:00:00',
  canResume: false,
  activeCommandId: null,
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/job-practice/session/session-1']}>
      <Routes>
        <Route path="/job-practice/session/:sessionId" element={<JobInterviewRuntimePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('JobInterviewRuntimePage paused state', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(jobInterviewApi.getSession).mockResolvedValue(pausedSession);
    vi.mocked(subscribeJobInterviewEvents).mockReturnValue(() => undefined);
  });

  it('恢复次数用尽后隐藏必失败的继续按钮并保留交卷与中止出口', async () => {
    renderPage();

    expect(await screen.findByText('本场已使用过一次恢复机会，可以提前交卷生成报告，或中止本次面试。'))
      .toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '继续面试' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '结束并生成报告' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '中止' })).toBeInTheDocument();
  });

  it('仍可恢复时继续展示恢复入口，同时保留收敛动作', async () => {
    vi.mocked(jobInterviewApi.getSession).mockResolvedValue({
      ...pausedSession,
      canResume: true,
    });

    renderPage();

    expect(await screen.findByRole('button', { name: '继续面试' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '结束并生成报告' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '中止' })).toBeInTheDocument();
  });
});

describe('JobInterviewRuntimePage algorithm draft', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(subscribeJobInterviewEvents).mockReturnValue(() => undefined);
    vi.mocked(jobInterviewApi.getSession).mockResolvedValue({
      ...pausedSession,
      status: 'IN_PROGRESS',
      stage: 'ALGORITHM',
      currentQuestion: {
        questionId: 99,
        questionIndex: 5,
        stage: 'ALGORITHM',
        question: '最大连续子数组和',
        budgetSeconds: 900,
        followUp: false,
        parentQuestionId: null,
        capabilityName: '动态规划',
      },
      resumeExpiresAt: null,
      canResume: false,
    });
    vi.mocked(jobInterviewApi.getCodeDraft).mockResolvedValue({
      questionId: 99,
      language: 'JAVA21',
      functionSignature: 'int maxSubArray(int[] nums)',
      sourceCode: '',
      sourceHash: null,
      judgeStatus: 'INITIAL',
      judgeSubmissionId: null,
      updatedAt: null,
      submittedAt: null,
    });
  });

  it('首次进入算法题时不把 INITIAL 模板误报为已恢复草稿', async () => {
    renderPage();

    await waitFor(() => expect(jobInterviewApi.getCodeDraft).toHaveBeenCalledWith('session-1', 99));
    expect(screen.queryByText(/已恢复代码草稿/)).not.toBeInTheDocument();
  });
});
