import { request } from './request';

export interface RagTraceRun {
  id: number;
  ragRunId?: string | null;
  traceId: string;
  userId: number;
  sessionId: string | null;
  question: string;
  status: string;
  routeSource: string | null;
  routeIntent: string | null;
  latencyMs: number | null;
  answerSummary: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface RagTraceStage {
  id: number;
  ragRunId: string;
  stage: string;
  status: string;
  dataSource: string | null;
  inputSummary: string | null;
  outputSummary: string | null;
  metadataJson: string | null;
  provider: string | null;
  modelName: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  filterJson: string | null;
  fallbackReason: string | null;
  startedAt: string;
  completedAt: string | null;
  latencyMs: number | null;
}

export interface RagTraceCandidate {
  id: number;
  ragRunId: string;
  stage: string;
  rankNo: number | null;
  sourceType: string | null;
  documentId: string | null;
  segmentId: string | null;
  evidenceId: string | null;
  score: number | null;
  rerankScore: number | null;
  snippet: string | null;
  metadataJson: string | null;
  permissionAllowed: boolean | null;
  versionMatched: boolean | null;
  filterReason: string | null;
}

export interface RagTraceCitation {
  id: number;
  ragRunId: string;
  citationIndex: number;
  evidenceId: string | null;
  sourceLocator: string | null;
  cited: boolean;
  valid: boolean;
  confidence: number | null;
}

export interface RagAnswerSnapshot {
  id: number;
  ragRunId: string;
  answer: string | null;
  groundedStatus: string | null;
  confidence: number | null;
  invalidCitationsJson: string | null;
  tokenCount: number | null;
  createdAt: string;
}

export interface RagTraceDetail {
  run: RagTraceRun;
  stages: RagTraceStage[];
  candidates: RagTraceCandidate[];
  citations: RagTraceCitation[];
  answer: RagAnswerSnapshot | null;
}

export const ragTraceApi = {
  list(limit = 20): Promise<RagTraceRun[]> {
    return request.get<RagTraceRun[]>(`/api/v1/rag/traces?limit=${limit}`);
  },
  get(traceId: string): Promise<RagTraceDetail> {
    return request.get<RagTraceDetail>(`/api/v1/rag/traces/${encodeURIComponent(traceId)}`);
  },
};
