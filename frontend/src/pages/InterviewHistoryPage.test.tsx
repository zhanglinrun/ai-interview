import {render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {interviewApi, type TextSessionMeta} from '../api/interview';
import {jobTargetApi} from '../api/jobTarget';
import InterviewHistoryPage from './InterviewHistoryPage';

vi.mock('../api/interview', () => ({
  interviewApi: {
    listSessions: vi.fn(),
  },
}));

vi.mock('../api/history', () => ({
  historyApi: {
    deleteInterview: vi.fn(),
    exportInterviewPdf: vi.fn(),
  },
}));

vi.mock('../api/jobTarget', () => ({
  jobTargetApi: {
    list: vi.fn(),
  },
}));

const jobInterview: TextSessionMeta = {
  sessionId: 'job-session-1',
  skillId: '',
  difficulty: 'MEDIUM',
  resumeId: null,
  totalQuestions: 5,
  status: 'COMPLETED',
  evaluateStatus: 'COMPLETED',
  evaluateError: null,
  overallScore: null,
  jobInterview: true,
  jobDescriptionId: 1,
  currentStage: 'ENGINEERING_SCENARIO',
  sessionVersion: 1,
  createdAt: '2026-07-15T09:00:00',
  completedAt: '2026-07-15T10:00:00',
};

const regularInterview: TextSessionMeta = {
  ...jobInterview,
  sessionId: 'regular-session-1',
  skillId: 'java-backend',
  overallScore: 80,
  jobInterview: false,
  jobDescriptionId: null,
  currentStage: null,
};

describe('InterviewHistoryPage', () => {
  beforeEach(() => {
    vi.mocked(interviewApi.listSessions).mockResolvedValue([jobInterview]);
    vi.mocked(jobTargetApi.list).mockResolvedValue([{
      id: 1,
      targetKey: 'java-rag',
      version: 1,
      title: 'Java 后端工程师',
      company: '示例科技',
      jobTrack: 'JAVA_BACKEND',
      jdText: null,
      contentHash: 'hash',
      status: 'FROZEN',
      createdAt: '2026-07-15T08:00:00',
      capabilities: [],
    }]);
  });

  it('只有不设总分的岗位实战时不把平均分展示为 0 分，并显示中文阶段', async () => {
    render(
      <InterviewHistoryPage
        onBack={vi.fn()}
        onViewInterview={vi.fn()}
      />,
    );

    expect(await screen.findByText('示例科技 · Java 后端工程师')).toBeInTheDocument();
    expect(screen.getByText('暂无可计算分数')).toBeInTheDocument();
    expect(screen.queryByText(/^0$/)).not.toBeInTheDocument();
    expect(screen.getByText('工程场景')).toBeInTheDocument();
    expect(screen.queryByText('ENGINEERING_SCENARIO')).not.toBeInTheDocument();
  });

  it('岗位实战不参与已有文字面试的平均分计算', async () => {
    vi.mocked(interviewApi.listSessions).mockResolvedValue([
      {...jobInterview, overallScore: 0},
      regularInterview,
    ]);

    render(
      <InterviewHistoryPage
        onBack={vi.fn()}
        onViewInterview={vi.fn()}
      />,
    );

    await screen.findByText('示例科技 · Java 后端工程师');
    const averageLabel = screen.getByText('平均分数');
    expect(averageLabel.parentElement).toHaveTextContent('80分');
  });
});
