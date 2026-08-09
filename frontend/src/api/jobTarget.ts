import { AI_REQUEST_TIMEOUT_MS, request } from './request';

export type JobTrack = 'JAVA_BACKEND' | 'AI_RAG_AGENT';
export type JobDescriptionStatus = 'DRAFT' | 'ANALYZED' | 'FROZEN' | 'REDACTED';

export interface JobCapabilityMapping {
  id: number;
  atomId: string;
  atomVersion: string;
  capabilityName: string;
  mappingSource: string;
  evidenceText?: string | null;
  evidenceStart?: number | null;
  evidenceEnd?: number | null;
  suggestedWeight: number;
  confirmedWeight?: number | null;
  confidence?: number | null;
  enabled: boolean;
}

export interface JobTarget {
  id: number;
  targetKey: string;
  version: number;
  title: string;
  company?: string | null;
  jobTrack: JobTrack;
  /** 列表接口为减小响应体返回 null，详情接口返回原文；脱敏版本始终为 null。 */
  jdText: string | null;
  sourceUrl?: string | null;
  contentHash: string;
  status: JobDescriptionStatus;
  templateCode?: string | null;
  templateVersion?: string | null;
  frozenAt?: string | null;
  createdAt: string;
  capabilities: JobCapabilityMapping[];
}

export interface CreateJobTargetRequest {
  title: string;
  company?: string;
  jobTrack: JobTrack;
  jdText: string;
  sourceUrl?: string;
}

export interface JdAnalysisResult {
  jobDescriptionId: number;
  fallbackUsed: boolean;
  warning?: string | null;
  capabilities: JobCapabilityMapping[];
}

export interface CapabilityAdjustment {
  mappingId: number;
  enabled: boolean;
  weight: number;
}

export const jobTargetApi = {
  list: () => request.get<JobTarget[]>('/api/v1/job-targets'),
  get: (id: number) => request.get<JobTarget>(`/api/v1/job-targets/${id}`),
  create: (body: CreateJobTargetRequest) => request.post<JobTarget>('/api/v1/job-targets', body),
  createVersion: (id: number, jdText: string, sourceUrl?: string) =>
    request.post<JobTarget>(`/api/v1/job-targets/${id}/versions`, { jdText, sourceUrl }),
  analyze: (id: number) => request.post<JdAnalysisResult>(
    `/api/v1/job-targets/${id}/analyze`,
    undefined,
    { timeout: AI_REQUEST_TIMEOUT_MS },
  ),
  confirmCapabilities: (id: number, adjustments: CapabilityAdjustment[]) =>
    request.put<JobCapabilityMapping[]>(`/api/v1/job-targets/${id}/capabilities`, {
      adjustments,
      temporaryCapability: null,
    }),
  freeze: (id: number) => request.post<JobTarget>(`/api/v1/job-targets/${id}/freeze`),
  delete: (id: number) => request.delete<void>(`/api/v1/job-targets/${id}`),
};
