import { API_BASE_URL, getAuthHeaders, request } from './request';
import { createTraceId, rememberTraceId } from '../stores/traceStore';

export type PreparationStatus = 'DRAFT' | 'PREPARING' | 'READY' | 'FAILED';
export type JobInterviewStatus =
  | 'READY'
  | 'IN_PROGRESS'
  | 'PAUSED'
  | 'COMPLETING'
  | 'COMPLETED'
  | 'ABORTED'
  | 'FAILED';
export type JobInterviewStage =
  | 'PROJECT_DEEP_DIVE'
  | 'POSITION_TECH'
  | 'ALGORITHM'
  | 'ENGINEERING_SCENARIO';

export interface CreatePreparationRequest {
  jobDescriptionId: number;
  resumeId: number | null;
  githubRepositoryId: number | null;
  knowledgeBaseIds: number[];
  includePersonalMaterials: boolean;
  codingLanguage: 'JAVA21' | 'PYTHON3';
  regenerate: boolean;
}

export interface StageView {
  stage: JobInterviewStage;
  budgetSeconds: number;
}

export interface PreparationView {
  runId: string;
  status: PreparationStatus;
  jobDescriptionId: number;
  jobTitle: string;
  templateCode: string;
  templateVersion: string;
  codingLanguage: 'JAVA21' | 'PYTHON3';
  personalKnowledgeEnabled: boolean;
  resumeBound: boolean;
  githubBound: boolean;
  stages: StageView[];
  degradedReasons: string[];
  dependencyStatus: Record<string, string>;
  sessionId?: string | null;
  sessionVersion?: number | null;
  failureCode?: string | null;
  failureDetail?: string | null;
  createdAt: string;
  completedAt?: string | null;
  reused: boolean;
}

export interface JobInterviewQuestion {
  questionId: number;
  questionIndex: number;
  stage: JobInterviewStage;
  question: string;
  budgetSeconds: number;
  followUp: boolean;
  parentQuestionId?: number | null;
  capabilityName?: string | null;
}

export interface JobInterviewAssessment {
  status: 'PENDING' | 'COMPLETED' | 'NEEDS_REVIEW';
  technicalCorrectness?: number | null;
  completeness?: number | null;
  factualConsistency?: string | null;
  evidenceStatus?: string | null;
  confidence: number;
  recommendedAction?: 'DEEPEN' | 'CLARIFY' | 'REMEDIATE' | 'SWITCH_TOPIC' | null;
  rationale?: string | null;
  objectiveEvidenceIds: string[];
  latencyMs?: number | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  retryCount?: number | null;
  degradedReason?: string | null;
}

export interface JobInterviewSession {
  sessionId: string;
  status: JobInterviewStatus;
  sessionVersion: number;
  stage: JobInterviewStage;
  jobDescriptionId: number;
  jobDescriptionVersion: number;
  capabilityTemplateCode: string;
  capabilityTemplateVersion: string;
  planVersion: string;
  promptVersion: string;
  githubCommitSha?: string | null;
  codingLanguage: 'JAVA21' | 'PYTHON3';
  personalKnowledgeEnabled: boolean;
  degradedReasons: string[];
  currentQuestion?: JobInterviewQuestion | null;
  answeredQuestions: number;
  totalQuestions: number;
  stageDeadlineAt?: string | null;
  softDeadlineAt?: string | null;
  resumeExpiresAt?: string | null;
  canResume: boolean;
  activeCommandId?: string | null;
}

export interface CommandResult {
  commandId: string;
  commandType: string;
  commandStatus: 'PROCESSING' | 'COMPLETED' | 'FAILED';
  sessionId: string;
  sessionVersion: number;
  sessionStatus: JobInterviewStatus;
  stage: JobInterviewStage;
  message?: string | null;
  currentQuestion?: JobInterviewQuestion | null;
  assessment?: JobInterviewAssessment | null;
  eventId?: number | null;
  duplicate: boolean;
  degradedReasons: string[];
}

export interface JobInterviewEvent {
  eventId: number;
  eventType: string;
  sessionVersion: number;
  payload: Record<string, unknown>;
  createdAt: string;
  sourceTraceId?: string | null;
}

export interface JobInterviewCodeDraft {
  questionId: number;
  language: 'JAVA21' | 'PYTHON3';
  functionSignature: string;
  sourceCode: string;
  sourceHash?: string | null;
  judgeStatus?: string | null;
  judgeSubmissionId?: string | null;
  updatedAt?: string | null;
  submittedAt?: string | null;
}

/**
 * Creates the idempotency key at the beginning of a user operation.  Callers
 * may keep the returned value and pass it again when a network retry is
 * needed; the server then returns the original command result.
 */
export function createJobInterviewCommandId(prefix: string): string {
  const suffix = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${suffix}`.slice(0, 64);
}

function resolveCommandId(prefix: string, value?: string): string {
  return value && value.trim() ? value.trim().slice(0, 64) : createJobInterviewCommandId(prefix);
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function waitForPreparation(runId: string): Promise<PreparationView> {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const view = await jobInterviewApi.getPreparation(runId);
    if (view.status === 'READY') return view;
    if (view.status === 'FAILED') {
      throw new Error(view.failureDetail || view.failureCode || '岗位实战准备失败');
    }
    await delay(1_000);
  }
  throw new Error('准备任务仍在运行，请稍后重试；已生成的任务不会丢失');
}

/**
 * 使用 fetch 携带 Bearer token 读取 SSE；EventSource 无法自定义 Authorization。
 * 连接断开后用最后一个持久化 eventId 续传，不依赖内存中的瞬时事件。
 */
export function subscribeJobInterviewEvents(
  sessionId: string,
  initialEventId: number,
  onEvent: (event: JobInterviewEvent) => void,
  onConnectionChange: (connected: boolean) => void,
): () => void {
  const controller = new AbortController();
  let cursor = Math.max(0, initialEventId);

  const connect = async () => {
    while (!controller.signal.aborted) {
      try {
        const response = await fetch(
          `${API_BASE_URL}/api/v1/job-interviews/sessions/${encodeURIComponent(sessionId)}/events?afterEventId=${cursor}`,
          {
            headers: {
              Accept: 'text/event-stream',
              'Last-Event-ID': String(cursor),
              'X-Trace-Id': createTraceId(),
              ...getAuthHeaders(),
            },
            signal: controller.signal,
          },
        );
        rememberTraceId(response.headers.get('X-Trace-Id'));
        if (!response.ok || !response.body) {
          throw new Error(`SSE ${response.status}`);
        }
        onConnectionChange(true);
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (!controller.signal.aborted) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
          let boundary = buffer.indexOf('\n\n');
          while (boundary >= 0) {
            const block = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);
            const data = block.split('\n')
              .filter((line) => line.startsWith('data:'))
              .map((line) => line.slice(5).trimStart())
              .join('\n');
            if (data) {
              const event = JSON.parse(data) as JobInterviewEvent;
              cursor = Math.max(cursor, event.eventId);
              onEvent(event);
            }
            boundary = buffer.indexOf('\n\n');
          }
        }
      } catch {
        if (controller.signal.aborted) return;
      }
      onConnectionChange(false);
      await delay(1_500);
    }
  };

  void connect();
  return () => controller.abort();
}

export const jobInterviewApi = {
  createPreparation: (body: CreatePreparationRequest) => request.post<PreparationView>(
    '/api/v1/job-interviews/preparations',
    body,
  ),
  getPreparation: (runId: string) => request.get<PreparationView>(
    `/api/v1/job-interviews/preparations/${encodeURIComponent(runId)}`,
  ),
  waitForPreparation,
  getSession: (sessionId: string) => request.get<JobInterviewSession>(
    `/api/v1/job-interviews/sessions/${encodeURIComponent(sessionId)}`,
  ),
  getCodeDraft: (sessionId: string, questionId: number) => request.get<JobInterviewCodeDraft | null>(
    `/api/v1/job-interviews/sessions/${encodeURIComponent(sessionId)}/code`,
    { params: { questionId } },
  ),
  start: (sessionId: string, expectedSessionVersion: number, commandId?: string) => request.post<CommandResult>(
    `/api/v1/job-interviews/sessions/${encodeURIComponent(sessionId)}/start`,
    { commandId: resolveCommandId('start', commandId), expectedSessionVersion },
  ),
  submitAnswer: (
    sessionId: string,
    expectedSessionVersion: number,
    questionId: number,
    answer: string,
    commandId?: string,
  ) => request.post<CommandResult>(
    `/api/v1/job-interviews/sessions/${encodeURIComponent(sessionId)}/answers`,
    { commandId: resolveCommandId('answer', commandId), expectedSessionVersion, questionId, answer },
  ),
  clarify: (
    sessionId: string,
    expectedSessionVersion: number,
    question: string,
    commandId?: string,
  ) => request.post<CommandResult>(
    `/api/v1/job-interviews/sessions/${encodeURIComponent(sessionId)}/clarification`,
    { commandId: resolveCommandId('clarify', commandId), expectedSessionVersion, question: question || null },
  ),
  saveCode: (
    sessionId: string,
    expectedSessionVersion: number,
    questionId: number,
    sourceCode: string,
    commandId?: string,
  ) => request.put<CommandResult>(
    `/api/v1/job-interviews/sessions/${encodeURIComponent(sessionId)}/code`,
    { commandId: resolveCommandId('save-code', commandId), expectedSessionVersion, questionId, sourceCode },
  ),
  submitCode: (
    sessionId: string,
    expectedSessionVersion: number,
    questionId: number,
    sourceCode: string,
    commandId?: string,
  ) => request.post<CommandResult>(
    `/api/v1/job-interviews/sessions/${encodeURIComponent(sessionId)}/code/submit`,
    { commandId: resolveCommandId('submit-code', commandId), expectedSessionVersion, questionId, sourceCode },
  ),
  continue: (sessionId: string, expectedSessionVersion: number, commandId?: string) => request.post<CommandResult>(
    `/api/v1/job-interviews/sessions/${encodeURIComponent(sessionId)}/continue`,
    { commandId: resolveCommandId('continue', commandId), expectedSessionVersion },
  ),
  finish: (sessionId: string, expectedSessionVersion: number, commandId?: string) => request.post<CommandResult>(
    `/api/v1/job-interviews/sessions/${encodeURIComponent(sessionId)}/finish`,
    { commandId: resolveCommandId('finish', commandId), expectedSessionVersion },
  ),
  abort: (sessionId: string, expectedSessionVersion: number, reason: string, commandId?: string) =>
    request.post<CommandResult>(
      `/api/v1/job-interviews/sessions/${encodeURIComponent(sessionId)}/abort`,
      { commandId: resolveCommandId('abort', commandId), expectedSessionVersion, reason },
    ),
};
