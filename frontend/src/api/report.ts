import { request } from './request';

export type CapabilityState = 'UNVERIFIED' | 'WEAK' | 'STABLE' | 'STRENGTH' | 'REVIEW';

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

export const reportApi = {
  listCapabilityProfile: () => request.get<CapabilityProfileItem[]>('/api/v1/capability-profile'),
  listLlmUsage: (params?: { sessionId?: string; reportId?: string; limit?: number }) =>
    request.get<LlmUsageItem[]>('/api/v1/llm-usage', { params }),
};
