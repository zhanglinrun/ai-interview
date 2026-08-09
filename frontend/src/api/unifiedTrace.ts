import { request } from './request';

export interface UnifiedTrace {
  traceId: string | null;
  sessionId: string | null;
  operation: string | null;
  agentRuns: AgentRunView[];
  ragRuns: RagRunView[];
  toolRuns: ToolRunView[];
  llmUsage: LlmUsageView[];
  timeline: TimelineEvent[];
}

export interface AgentRunView {
  agentRunId: string;
  commandId: string | null;
  operation: string | null;
  status: string;
  latencyMs: number | null;
  degradedReason: string | null;
  createdAt: string | null;
  completedAt: string | null;
  steps: AgentStepView[];
}

export interface AgentStepView {
  spanId: string | null;
  parentSpanId: string | null;
  role: string | null;
  action: string | null;
  status: string | null;
  latencyMs: number | null;
  stepOrder: number | null;
  observation: string | null;
  createdAt: string | null;
}

export interface RagRunView {
  ragRunId: string;
  agentRunId: string | null;
  status: string;
  latencyMs: number | null;
  degradedReason: string | null;
  question: string | null;
  answerSummary: string | null;
  createdAt: string | null;
  stages: RagStageView[];
  evidenceIds: string[];
}

export interface RagStageView {
  stage: string | null;
  status: string | null;
  dataSource: string | null;
  inputSummary: string | null;
  outputSummary: string | null;
  fallbackReason: string | null;
  latencyMs: number | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface ToolRunView {
  toolRunId: string;
  toolName: string;
  status: string;
  agentRunId: string | null;
  ragRunId: string | null;
  cacheHit: boolean | null;
  retryCount: number | null;
  latencyMs: number | null;
  outputSummary: string | null;
  fallbackReason: string | null;
  errorCode: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface LlmUsageView {
  usageId: string;
  operation: string;
  provider: string;
  model: string | null;
  status: string;
  latencyMs: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  retryCount: number | null;
  degradedReason: string | null;
  agentRunId: string | null;
  ragRunId: string | null;
  spanId: string | null;
  createdAt: string | null;
}

export interface TimelineEvent {
  kind: string;
  id: string;
  status: string;
  latencyMs: number | null;
  at: string | null;
  metadata: Record<string, string>;
}

export const unifiedTraceApi = {
  get(traceId: string, limit = 100, offset = 0): Promise<UnifiedTrace> {
    return request.get<UnifiedTrace>(
      `/api/v1/traces/${encodeURIComponent(traceId)}?limit=${limit}&offset=${offset}`);
  },
  timeline(sessionId: string, limit = 200, offset = 0): Promise<UnifiedTrace> {
    return request.get<UnifiedTrace>(
      `/api/v1/interviews/sessions/${encodeURIComponent(sessionId)}/timeline?limit=${limit}&offset=${offset}`);
  },
};
