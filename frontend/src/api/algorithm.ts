import { AI_REQUEST_TIMEOUT_MS, request } from './request';

export type CodingLanguage = 'JAVA21' | 'PYTHON3';
export type CodingAttemptMode = 'JOB_INTERVIEW' | 'TRAINING';
export type JudgeStatus =
  | 'QUEUED'
  | 'PROCESSING'
  | 'ACCEPTED'
  | 'WRONG_ANSWER'
  | 'COMPILE_ERROR'
  | 'RUNTIME_ERROR'
  | 'TIME_LIMIT_EXCEEDED'
  | 'MEMORY_LIMIT_EXCEEDED'
  | 'INTERNAL_ERROR'
  | 'UNAVAILABLE';

export interface CodingProblemSummary {
  problemId: number;
  /** 只有已接入平台题面的题目才有内部作答版本。 */
  problemVersionId: number | null;
  hotRank: number;
  platformProblemId: string;
  title: string;
  difficulty: 'EASY' | 'MEDIUM' | 'HARD';
  tags: string[];
  sourceUrl?: string | null;
  version: string | null;
  enabledLanguages: CodingLanguage[];
}

export interface CodingProblemDetail extends Omit<CodingProblemSummary, 'enabledLanguages'> {
  statement: string;
  constraints: string[];
  publicExamples: Array<{ input: string; output: string; explanation?: string | null }>;
  complexityRubric?: {
    expectedTime: string;
    expectedSpace: string;
    discussionPoints: string[];
  } | null;
  languages: Array<{
    language: CodingLanguage;
    functionSignature: string;
    template: string;
  }>;
}

export interface CodingAttempt {
  attemptId: string;
  problemVersionId: number;
  mode: CodingAttemptMode;
  contextId?: string | null;
  language: CodingLanguage;
  status: string;
  startedAt: string;
  submittedAt?: string | null;
  completedAt?: string | null;
}

export interface CodingDraft {
  attemptId: string;
  language: CodingLanguage;
  sourceCode: string;
  revision: number;
  updatedAt: string;
}

export interface JudgeSubmission {
  submissionId: string;
  attemptId: string;
  suiteType: 'PUBLIC' | 'HIDDEN';
  language: CodingLanguage;
  status: JudgeStatus;
  passedCount?: number | null;
  totalCount?: number | null;
  diagnostic?: string | null;
  timeMs?: number | null;
  memoryKb?: number | null;
  failureCode?: string | null;
  pendingRejudge: boolean;
  submittedAt: string;
  completedAt?: string | null;
}

export const algorithmApi = {
  listProblems: (params?: { language?: CodingLanguage; tag?: string }) =>
    request.get<CodingProblemSummary[]>('/api/v1/algorithms/problems', { params }),
  getProblem: (problemVersionId: number) => request.get<CodingProblemDetail>(
    `/api/v1/algorithms/problem-versions/${problemVersionId}`,
  ),
  createAttempt: (problemVersionId: number, language: CodingLanguage) =>
    request.post<CodingAttempt>('/api/v1/algorithms/attempts', {
      problemVersionId,
      language,
      mode: 'TRAINING' satisfies CodingAttemptMode,
      contextId: null,
    }),
  getAttempt: (attemptId: string) => request.get<CodingAttempt>(
    `/api/v1/algorithms/attempts/${encodeURIComponent(attemptId)}`,
  ),
  getDraft: (attemptId: string) => request.get<CodingDraft>(
    `/api/v1/algorithms/attempts/${encodeURIComponent(attemptId)}/draft`,
  ),
  saveDraft: (attemptId: string, sourceCode: string, expectedRevision: number) =>
    request.put<CodingDraft>(`/api/v1/algorithms/attempts/${encodeURIComponent(attemptId)}/draft`, {
      expectedRevision,
      sourceCode,
    }),
  run: (attemptId: string, sourceCode: string, idempotencyKey: string) =>
    request.post<JudgeSubmission>(
      `/api/v1/algorithms/attempts/${encodeURIComponent(attemptId)}/run`,
      { sourceCode, idempotencyKey },
      { timeout: AI_REQUEST_TIMEOUT_MS },
    ),
  submit: (attemptId: string, sourceCode: string, idempotencyKey: string) =>
    request.post<JudgeSubmission>(
      `/api/v1/algorithms/attempts/${encodeURIComponent(attemptId)}/submissions`,
      { sourceCode, idempotencyKey },
      { timeout: AI_REQUEST_TIMEOUT_MS },
    ),
  listSubmissions: (attemptId: string) => request.get<JudgeSubmission[]>(
    `/api/v1/algorithms/attempts/${encodeURIComponent(attemptId)}/submissions`,
  ),
  rejudge: (submissionId: string) => request.post<JudgeSubmission>(
    `/api/v1/algorithms/submissions/${encodeURIComponent(submissionId)}/rejudge`,
    undefined,
    { timeout: AI_REQUEST_TIMEOUT_MS },
  ),
};
