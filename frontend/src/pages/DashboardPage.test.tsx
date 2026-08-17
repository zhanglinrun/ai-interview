import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { historyApi, type ResumeListItem } from '../api/history';
import { interviewApi, type TextSessionMeta } from '../api/interview';
import { interviewScheduleApi } from '../api/interviewSchedule';
import { knowledgeBaseApi } from '../api/knowledgebase';
import type { InterviewMemory, LongTermMemoryItem } from '../types/interview';
import type { InterviewSchedule } from '../types/interviewSchedule';
import DashboardPage, {
  buildDashboardNextStep,
  canContinueSession,
  getSkillLabel,
  pickUpcomingSchedules,
  pickWeakLongTermMemories,
} from './DashboardPage';

vi.mock('../api/interview', () => ({
  interviewApi: {
    listSessions: vi.fn(),
    getMemory: vi.fn(),
  },
}));

vi.mock('../api/history', () => ({
  historyApi: {
    getResumes: vi.fn(),
  },
}));

vi.mock('../api/knowledgebase', () => ({
  knowledgeBaseApi: {
    getStatistics: vi.fn(),
  },
}));

vi.mock('../api/interviewSchedule', () => ({
  interviewScheduleApi: {
    getAll: vi.fn(),
  },
}));

vi.mock('../stores/authStore', () => ({
  useAuthStore: () => ({ userId: 1, username: 'linrun', displayName: '林润' }),
}));

const inProgressSession: TextSessionMeta = {
  sessionId: 'session-open',
  skillId: 'java-backend',
  difficulty: 'MEDIUM',
  resumeId: 3,
  totalQuestions: 8,
  status: 'IN_PROGRESS',
  evaluateStatus: null,
  evaluateError: null,
  overallScore: null,
  sessionVersion: 1,
  createdAt: '2026-08-16T08:00:00',
  completedAt: null,
};

const evaluatedSession: TextSessionMeta = {
  ...inProgressSession,
  sessionId: 'session-done',
  status: 'EVALUATED',
  evaluateStatus: 'COMPLETED',
  overallScore: 82,
  completedAt: '2026-08-16T09:00:00',
};

const resume: ResumeListItem = {
  id: 3,
  filename: 'backend-resume.pdf',
  fileSize: 1024,
  uploadedAt: '2026-08-10T10:00:00',
  accessCount: 1,
  interviewCount: 2,
};

const upcomingSchedule: InterviewSchedule = {
  id: 9,
  companyName: '字节跳动',
  position: '后端开发',
  interviewTime: '2026-08-20T14:00:00',
  interviewType: 'VIDEO',
  roundNumber: 2,
  status: 'PENDING',
  createdAt: '2026-08-15T10:00:00',
  updatedAt: '2026-08-15T10:00:00',
};

const weakMemory: LongTermMemoryItem = {
  topic: '检索与证据编排',
  capabilityAtomId: 'template:ai-rag-agent:rag-retrieval',
  masteryLevel: 'WEAKNESS',
  verificationState: 'PROVISIONAL',
  averageScore: 55,
  observationCount: 2,
  sessionCount: 1,
  latestEvidence: '证据引用不完整',
  lastAt: '2026-08-15T10:00:00',
};

function emptyMemory(longTerm: LongTermMemoryItem[] = []): InterviewMemory {
  return {
    shortTerm: {
      sessionId: null,
      skillId: null,
      live: false,
      windowSize: 4,
      agentMessageCount: 0,
      turns: [],
    },
    compressed: { sessionId: null, skillId: null, turns: [] },
    longTerm,
  };
}

function emptyStats() {
  return {
    totalCount: 0,
    totalQuestionCount: 0,
    totalAccessCount: 0,
    completedCount: 0,
    processingCount: 0,
  };
}

describe('dashboard helpers', () => {
  it('未完成且未评估完的场次可以继续', () => {
    expect(canContinueSession(inProgressSession)).toBe(true);
    expect(canContinueSession(evaluatedSession)).toBe(false);
    expect(canContinueSession({ status: 'COMPLETING', evaluateStatus: 'PROCESSING' })).toBe(false);
  });

  it('把已知 skillId 显示成方向名', () => {
    expect(getSkillLabel('java-backend')).toBe('Java 后端');
    expect(getSkillLabel('ai-rag-agent')).toBe('AI / RAG / Agent');
    expect(getSkillLabel('')).toBe('文字面试');
  });

  it('只保留未开始且未过期的日程', () => {
    const now = new Date('2026-08-16T12:00:00').getTime();
    const picked = pickUpcomingSchedules([
      upcomingSchedule,
      { ...upcomingSchedule, id: 10, interviewTime: '2026-08-16T09:00:00' },
      { ...upcomingSchedule, id: 11, status: 'COMPLETED' },
    ], now);
    expect(picked.map((item) => item.id)).toEqual([9]);
  });

  it('只挑长期记忆里的薄弱项，已验证排在待复测前面', () => {
    const picked = pickWeakLongTermMemories([
      { ...weakMemory, capabilityAtomId: 'developing', masteryLevel: 'DEVELOPING', topic: '发展中' },
      { ...weakMemory, capabilityAtomId: 'provisional', verificationState: 'PROVISIONAL', topic: '待复测薄弱' },
      { ...weakMemory, capabilityAtomId: 'verified', verificationState: 'VERIFIED', topic: '已验证薄弱' },
    ]);
    expect(picked.map((item) => item.topic)).toEqual(['已验证薄弱', '待复测薄弱']);
  });

  it('下一步优先继续未完成场次', () => {
    const step = buildDashboardNextStep({
      sessions: [evaluatedSession, inProgressSession],
      resumeCount: 1,
      knowledgeReadyCount: 2,
      upcoming: [upcomingSchedule],
      weakMemories: [weakMemory],
    });
    expect(step.primary.label).toBe('继续这场面试');
    expect(step.primary.state).toEqual({ sessionIdToResume: 'session-open' });
    expect(step.title).toContain('Java 后端');
  });

  it('没有未完成场次时提示临近的真实面试', () => {
    const step = buildDashboardNextStep({
      sessions: [evaluatedSession],
      resumeCount: 1,
      knowledgeReadyCount: 0,
      upcoming: [upcomingSchedule],
      weakMemories: [weakMemory],
    });
    expect(step.title).toContain('字节跳动');
    expect(step.primary.to).toBe('/interview');
    expect(step.secondary?.to).toBe('/interview-schedule');
  });

  it('没有简历时先引导上传', () => {
    const step = buildDashboardNextStep({
      sessions: [],
      resumeCount: 0,
      knowledgeReadyCount: 0,
      upcoming: [],
      weakMemories: [],
    });
    expect(step.primary).toEqual({ to: '/upload', label: '上传简历' });
    expect(step.secondary?.to).toBe('/interview');
  });

  it('没有未完成场次和日程时，用长期薄弱项引导再练', () => {
    const step = buildDashboardNextStep({
      sessions: [evaluatedSession],
      resumeCount: 1,
      knowledgeReadyCount: 0,
      upcoming: [],
      weakMemories: [weakMemory],
    });
    expect(step.title).toContain('检索与证据编排');
    expect(step.secondary).toEqual({ to: '/profile', label: '看三层记忆' });
  });
});

describe('DashboardPage', () => {
  beforeEach(() => {
    vi.mocked(interviewApi.listSessions).mockResolvedValue([]);
    vi.mocked(historyApi.getResumes).mockResolvedValue([]);
    vi.mocked(knowledgeBaseApi.getStatistics).mockResolvedValue(emptyStats());
    vi.mocked(interviewScheduleApi.getAll).mockResolvedValue([]);
    vi.mocked(interviewApi.getMemory).mockResolvedValue(emptyMemory());
  });

  it('有未完成场次时给出继续入口，并列出最近面试', async () => {
    vi.mocked(interviewApi.listSessions).mockResolvedValue([inProgressSession, evaluatedSession]);
    vi.mocked(historyApi.getResumes).mockResolvedValue([resume]);
    vi.mocked(knowledgeBaseApi.getStatistics).mockResolvedValue({
      ...emptyStats(),
      completedCount: 4,
    });

    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('link', { name: '继续这场面试' })).toHaveAttribute('href', '/interview');
    expect(screen.getByRole('heading', { name: /继续未完成的 Java 后端 面试/ })).toBeInTheDocument();
    expect(screen.getAllByText('Java 后端').length).toBeGreaterThan(0);
    expect(screen.getByText(/82 分/)).toBeInTheDocument();
    expect(screen.getByText('4 份可用')).toBeInTheDocument();
  });

  it('部分接口失败时仍能渲染，并提示未加载的数据', async () => {
    vi.mocked(interviewApi.listSessions).mockRejectedValue(new Error('down'));
    vi.mocked(historyApi.getResumes).mockResolvedValue([resume]);

    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('部分数据暂未加载：面试记录')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '上传简历' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '开始一场面试' })).toHaveAttribute('href', '/interview');
  });
});
