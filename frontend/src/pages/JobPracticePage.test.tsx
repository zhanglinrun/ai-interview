import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { githubEvidenceApi, type GithubRepository } from '../api/githubEvidence';
import { historyApi } from '../api/history';
import { jobTargetApi, type JobTarget } from '../api/jobTarget';
import { knowledgeBaseApi } from '../api/knowledgebase';
import JobPracticePage, { formatCapabilityWeight } from './JobPracticePage';

vi.mock('../api/jobTarget', () => ({
  jobTargetApi: {
    list: vi.fn(),
    get: vi.fn(),
  },
}));

vi.mock('../api/githubEvidence', () => ({
  githubEvidenceApi: { list: vi.fn() },
}));

vi.mock('../api/history', () => ({
  historyApi: { getResumes: vi.fn() },
}));

vi.mock('../api/knowledgebase', () => ({
  knowledgeBaseApi: { getAllKnowledgeBases: vi.fn() },
}));

vi.mock('../api/jobInterview', () => ({
  jobInterviewApi: {},
}));

describe('JobPracticePage', () => {
  const summary: JobTarget = {
    id: 1,
    targetKey: 'target-1',
    version: 1,
    title: 'Java 后端工程师',
    company: '示例公司',
    jobTrack: 'JAVA_BACKEND',
    jdText: null,
    sourceUrl: null,
    contentHash: 'sha256:summary',
    status: 'ANALYZED',
    templateCode: 'java-backend-v1',
    templateVersion: '1.0.0',
    frozenAt: null,
    createdAt: '2026-07-15T09:00:00',
    capabilities: [],
  };

  beforeEach(() => {
    vi.mocked(jobTargetApi.list).mockResolvedValue([summary]);
    vi.mocked(jobTargetApi.get).mockResolvedValue({
      ...summary,
      jdText: '负责 Java 后端服务、数据库与分布式系统的设计、开发、测试和稳定性建设。',
      capabilities: [{
        id: 11,
        atomId: 'JAVA_FOUNDATION',
        atomVersion: '1.0.0',
        capabilityName: 'Java 核心基础',
        mappingSource: 'JD_ANALYSIS',
        suggestedWeight: 0.5,
        confirmedWeight: null,
        confidence: 0.9,
        enabled: true,
      }],
    });
    vi.mocked(githubEvidenceApi.list).mockResolvedValue([]);
    vi.mocked(historyApi.getResumes).mockResolvedValue([]);
    vi.mocked(knowledgeBaseApi.getAllKnowledgeBases).mockResolvedValue([]);
  });

  it('岗位权重保留一位小数，避免归一化权重逐项取整后误显示为 102%', () => {
    expect(formatCapabilityWeight(0.147059)).toBe('14.7%');
    expect(formatCapabilityWeight(0.15)).toBe('15%');
  });

  it('列表摘要选中后读取详情，避免把空 capabilities 当成真实能力列表', async () => {
    render(<MemoryRouter><JobPracticePage /></MemoryRouter>);

    expect(await screen.findByText('Java 核心基础')).toBeInTheDocument();
    expect(jobTargetApi.get).toHaveBeenCalledWith(1);
    expect(knowledgeBaseApi.getAllKnowledgeBases).toHaveBeenCalledWith('time', 'VECTOR_STORED');
  });

  it('GitHub 部分同步时显示中文状态与安全策略原因', async () => {
    const repository: GithubRepository = {
      id: 9,
      owner: 'demo',
      repository: 'interview-project',
      repositoryUrl: 'https://github.com/demo/interview-project',
      defaultBranch: 'main',
      fixedCommitSha: 'a'.repeat(40),
      sourceSizeKb: 128,
      syncStatus: 'PARTIAL',
      syncedFileCount: 10,
      syncedBytes: 18_616,
      syncError: '1 个文件因安全策略未同步',
      sourceAvailable: true,
      selectionRequired: false,
      coreModules: ['RAG 检索'],
      responsibilities: '负责检索模块',
      keyDecisions: '固定 SHA',
      problemsSolved: '证据可追溯',
      createdAt: '2026-07-15T09:00:00',
      lastSyncedAt: '2026-07-15T09:10:00',
    };
    vi.mocked(githubEvidenceApi.list).mockResolvedValue([repository]);

    render(<MemoryRouter><JobPracticePage /></MemoryRouter>);

    expect(await screen.findByText('部分同步')).toBeInTheDocument();
    expect(screen.getByText('1 个文件因安全策略未同步')).toBeInTheDocument();
  });
});
