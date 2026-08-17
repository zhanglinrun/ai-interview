import {fireEvent, render, screen, waitFor} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {historyApi} from '../api/history';
import {interviewApi, type TextSessionMeta} from '../api/interview';
import InterviewHistoryPage, {
  matchesInterviewSearch,
  toInterviewHistoryItem,
} from './InterviewHistoryPage';

vi.mock('../api/interview', () => ({
  interviewApi: {
    listSessions: vi.fn(),
    reevaluateInterview: vi.fn(),
  },
}));

vi.mock('../api/history', () => ({
  historyApi: {
    deleteInterview: vi.fn(),
    exportInterviewPdf: vi.fn(),
  },
}));

const regularInterview: TextSessionMeta = {
  sessionId: 'regular-session-1',
  skillId: 'java-backend',
  difficulty: 'MEDIUM',
  resumeId: null,
  totalQuestions: 5,
  status: 'EVALUATED',
  evaluateStatus: 'COMPLETED',
  evaluateError: null,
  overallScore: 80,
  evaluationDegraded: false,
  sessionVersion: 1,
  createdAt: '2026-07-15T09:00:00',
  completedAt: '2026-07-15T10:00:00',
};

describe('InterviewHistoryPage', () => {
  beforeEach(() => {
    vi.mocked(interviewApi.listSessions).mockResolvedValue([regularInterview]);
  });

  it('展示文字面试分数并计算平均分', async () => {
    render(
      <InterviewHistoryPage
        onBack={vi.fn()}
        onViewInterview={vi.fn()}
      />,
    );

    expect(await screen.findByText('Java 后端')).toBeInTheDocument();
    const averageLabel = screen.getByText('平均分数');
    expect(averageLabel.parentElement).toHaveTextContent('80分');
    expect(screen.getByText('5 题')).toBeInTheDocument();
    expect(screen.getByText('用时 1小时')).toBeInTheDocument();
  });

  it('没有已评估分数时不把平均分展示为 0 分', async () => {
    vi.mocked(interviewApi.listSessions).mockResolvedValue([{
      ...regularInterview,
      status: 'IN_PROGRESS',
      evaluateStatus: null,
      overallScore: null,
    }]);

    render(
      <InterviewHistoryPage
        onBack={vi.fn()}
        onViewInterview={vi.fn()}
      />,
    );

    expect(await screen.findByText('Java 后端')).toBeInTheDocument();
    expect(screen.getByText('暂无可计算分数')).toBeInTheDocument();
  });

  it('降级 0 分不画真分并显示失败', async () => {
    vi.mocked(interviewApi.listSessions).mockResolvedValue([{
      ...regularInterview,
      overallScore: 0,
      evaluationDegraded: true,
    }]);

    render(
      <InterviewHistoryPage
        onBack={vi.fn()}
        onViewInterview={vi.fn()}
      />,
    );

    expect(await screen.findByText('Java 后端')).toBeInTheDocument();
    expect(screen.getByText('失败')).toBeInTheDocument();
    expect(screen.getByText('暂无可计算分数')).toBeInTheDocument();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });

  it('列表加载失败时展示错误而不是空记录', async () => {
    vi.mocked(interviewApi.listSessions).mockRejectedValue(new Error('网络连接失败，请检查网络'));

    render(
      <InterviewHistoryPage
        onBack={vi.fn()}
        onViewInterview={vi.fn()}
      />,
    );

    expect(await screen.findByText('网络连接失败，请检查网络')).toBeInTheDocument();
    expect(screen.getByText('重新加载')).toBeInTheDocument();
    expect(screen.queryByText('暂无面试记录')).not.toBeInTheDocument();
  });

  it('确认删除后调用删除接口并刷新列表', async () => {
    vi.mocked(historyApi.deleteInterview).mockResolvedValue(undefined);
    vi.mocked(interviewApi.listSessions)
      .mockResolvedValueOnce([regularInterview])
      .mockResolvedValueOnce([]);

    render(
      <InterviewHistoryPage
        onBack={vi.fn()}
        onViewInterview={vi.fn()}
      />,
    );

    expect(await screen.findByText('Java 后端')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', {name: '删除面试记录 Java 后端'}));
    fireEvent.click(screen.getByRole('button', {name: '确定删除'}));

    await waitFor(() => {
      expect(historyApi.deleteInterview).toHaveBeenCalledWith('regular-session-1');
    });
    expect(await screen.findByText('暂无面试记录')).toBeInTheDocument();
  });

  it('删除失败时把错误留在确认框里', async () => {
    vi.mocked(historyApi.deleteInterview).mockRejectedValue(new Error('删除面试记录失败，仍有关联数据未清理'));

    render(
      <InterviewHistoryPage
        onBack={vi.fn()}
        onViewInterview={vi.fn()}
      />,
    );

    expect(await screen.findByText('Java 后端')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', {name: '删除面试记录 Java 后端'}));
    fireEvent.click(screen.getByRole('button', {name: '确定删除'}));

    expect(await screen.findByRole('alert')).toHaveTextContent('删除面试记录失败，仍有关联数据未清理');
    expect(screen.getByText('Java 后端')).toBeInTheDocument();
  });

  it('用方向名和会话 ID 搜索', () => {
    const item = toInterviewHistoryItem(regularInterview);
    expect(item.title).toBe('Java 后端');
    expect(matchesInterviewSearch(item, 'java')).toBe(true);
    expect(matchesInterviewSearch(item, 'regular-session')).toBe(true);
    expect(matchesInterviewSearch(item, 'redis')).toBe(false);
  });
});
