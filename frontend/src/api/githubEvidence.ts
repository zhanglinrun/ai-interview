import { AI_REQUEST_TIMEOUT_MS, request } from './request';

export type GithubSyncStatus =
  | 'AWAITING_SELECTION'
  | 'SYNCING'
  | 'SYNCED'
  | 'PARTIAL'
  | 'FAILED'
  | 'SOURCE_UNAVAILABLE';

export interface GithubRepository {
  id: number;
  owner: string;
  repository: string;
  repositoryUrl: string;
  defaultBranch?: string | null;
  fixedCommitSha?: string | null;
  sourceSizeKb: number;
  syncStatus: GithubSyncStatus;
  syncedFileCount: number;
  syncedBytes: number;
  syncError?: string | null;
  sourceAvailable: boolean;
  selectionRequired: boolean;
  coreModules: string[];
  responsibilities: string;
  keyDecisions: string;
  problemsSolved: string;
  createdAt: string;
  lastSyncedAt?: string | null;
}

export interface GithubFileCandidate {
  path: string;
  byteSize: number;
  language?: string | null;
  fileKind: string;
  status: string;
  reason?: string | null;
  defaultIncluded: boolean;
}

export interface GithubRepositoryDetail {
  repository: GithubRepository;
  eligibleFileCount: number;
  eligibleBytes: number;
  files: GithubFileCandidate[];
}

export interface BindGithubRepositoryRequest {
  repositoryUrl: string;
  contribution: {
    coreModules: string[];
    responsibilities: string;
    keyDecisions: string;
    problemsSolved: string;
  };
}

export interface GithubSyncResult {
  repositoryId: number;
  commitSha: string;
  status: string;
  syncedFiles: number;
  syncedBytes: number;
  evidenceChunks: number;
  blockedFiles: number;
  reusedSnapshot: boolean;
}

export const githubEvidenceApi = {
  list: () => request.get<GithubRepository[]>('/api/v1/github/repositories'),
  detail: (id: number) => request.get<GithubRepositoryDetail>(`/api/v1/github/repositories/${id}`),
  bind: (body: BindGithubRepositoryRequest) => request.post<GithubRepositoryDetail>(
    '/api/v1/github/repositories',
    body,
    { timeout: AI_REQUEST_TIMEOUT_MS },
  ),
  sync: (id: number, expectedCommitSha: string, includePaths: string[] = []) =>
    request.post<GithubSyncResult>(`/api/v1/github/repositories/${id}/sync`, {
      expectedCommitSha,
      includePaths,
      excludePrefixes: [],
    }, { timeout: AI_REQUEST_TIMEOUT_MS }),
  delete: (id: number) => request.delete<void>(`/api/v1/github/repositories/${id}`),
};
