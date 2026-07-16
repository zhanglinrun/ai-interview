import { AI_REQUEST_TIMEOUT_MS, request } from './request';

export type ReportStatus = 'GENERATING' | 'COMPLETED' | 'FAILED';
export type CapabilityState = 'UNVERIFIED' | 'WEAK' | 'STABLE' | 'STRENGTH' | 'REVIEW';
export type TrainingType =
  | 'ALGORITHM'
  | 'PROJECT_DEEP_DIVE'
  | 'TECHNICAL_FOUNDATION'
  | 'ENGINEERING_SCENARIO';
export type TrainingStatus = 'RECOMMENDED' | 'IN_PROGRESS' | 'COMPLETED';

export interface ObjectiveFact {
  questionId: number;
  questionIndex: number;
  stage: string;
  question: string;
  answer?: string | null;
  assessmentStatus?: string | null;
  technicalCorrectness?: number | null;
  completeness?: number | null;
  factualConsistency?: string | null;
  evidenceStatus?: string | null;
  confidence?: number | null;
  judgeStatus?: string | null;
  passedCount?: number | null;
  totalCount?: number | null;
  codingLanguage?: string | null;
  executionTimeMs?: number | null;
  memoryKb?: number | null;
  feedback?: string | null;
  evidenceIds: string[];
  sourceAvailable: boolean;
}

export interface ReportView {
  reportId: string;
  sessionId: string;
  status: ReportStatus;
  objectiveFacts: ObjectiveFact[];
  summary?: {
    overallFeedback?: string | null;
    strengths: string[];
    improvements: string[];
  } | null;
  gaps: Array<{
    capabilityAtomId: string;
    capabilityName: string;
    reason: string;
    sourceQuestionId?: number | null;
    evidenceRecordIds: string[];
    trainingType: TrainingType;
    trainingTaskId?: string | null;
  }>;
  failureCode?: string | null;
  failureDetail?: string | null;
  generationAttempt: number;
  retryable: boolean;
  createdAt: string;
  completedAt?: string | null;
}

export interface CapabilityProfileItem {
  capabilityAtomId: string;
  capabilityName: string;
  state: CapabilityState;
  reviewRequired: boolean;
  evidenceCount: number;
  recentEvidenceRecordIds: string[];
  lastEvidenceAt?: string | null;
  updatedAt: string;
}

export interface TrainingTask {
  taskId: string;
  reportId?: string | null;
  capabilityAtomId: string;
  trainingType: TrainingType;
  status: TrainingStatus;
  sourceQuestionId?: number | null;
  question: string;
  questionVersion: string;
  evidenceScopes: string[];
  hintUsed: boolean;
  answerViewed: boolean;
  redoCount: number;
  resultScore?: number | null;
  createdAt: string;
  completedAt?: string | null;
}

export interface LlmUsageItem {
  usageId: string;
  sessionId?: string | null;
  reportId?: string | null;
  operation: string;
  provider?: string | null;
  model?: string | null;
  status: 'SUCCEEDED' | 'FAILED' | 'DEGRADED';
  latencyMs: number;
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  estimatedCost?: number | null;
  currency?: string | null;
  retryCount: number;
  degradedReason?: string | null;
  createdAt: string;
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function waitForReport(sessionId: string, initial: ReportView): Promise<ReportView> {
  if (initial.status !== 'GENERATING') return initial;
  for (let attempt = 0; attempt < 60; attempt += 1) {
    await delay(1_000);
    const report = await reportApi.get(sessionId);
    if (report.status !== 'GENERATING') return report;
  }
  throw new Error('报告仍在生成，可稍后从面试记录再次打开');
}

export const reportApi = {
  get: (sessionId: string) => request.get<ReportView>(
    `/api/reports/sessions/${encodeURIComponent(sessionId)}`,
  ),
  generate: (sessionId: string) => request.post<ReportView>(
    `/api/reports/sessions/${encodeURIComponent(sessionId)}/generate`,
    undefined,
    { timeout: AI_REQUEST_TIMEOUT_MS },
  ),
  retry: (sessionId: string) => request.post<ReportView>(
    `/api/reports/sessions/${encodeURIComponent(sessionId)}/retry`,
    undefined,
    { timeout: AI_REQUEST_TIMEOUT_MS },
  ),
  waitForReport,
  listCapabilityProfile: () => request.get<CapabilityProfileItem[]>('/api/capability-profile'),
  listTrainingTasks: (status?: TrainingStatus) => request.get<TrainingTask[]>(
    '/api/training/tasks',
    { params: status ? { status } : undefined },
  ),
  createTrainingTask: (body: {
    capabilityAtomId: string;
    trainingType: TrainingType;
    question?: string;
    evidenceScopes?: string[];
  }) => request.post<TrainingTask>('/api/training/tasks', body),
  recordTrainingInteraction: (
    taskId: string,
    body: { hintUsed?: boolean; answerViewed?: boolean; redo?: boolean },
  ) => request.post<TrainingTask>(
    `/api/training/tasks/${encodeURIComponent(taskId)}/interactions`,
    body,
  ),
  completeTrainingTask: (
    taskId: string,
    body: {
      score: number;
      objectivePassed?: boolean | null;
      hintUsed: boolean;
      answerViewed: boolean;
      redoCount: number;
      observation?: string;
    },
  ) => request.post<TrainingTask>(
    `/api/training/tasks/${encodeURIComponent(taskId)}/complete`,
    body,
  ),
  listLlmUsage: (params?: { sessionId?: string; reportId?: string; limit?: number }) =>
    request.get<LlmUsageItem[]>('/api/llm-usage', { params }),
};
