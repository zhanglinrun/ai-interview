import { AI_REQUEST_TIMEOUT_MS, request } from './request';

export interface RagIntentEntities {
  jobTrack: string | null;
  resumeId: number | null;
  sessionId: string | null;
  company: string | null;
}

export interface RagIntentResult {
  reason: string;
  related: boolean;
  intent: string;
  entities: RagIntentEntities | null;
}

export const ragModuleApi = {
  testIntent(question: string) {
    return request.get<RagIntentResult>(
      `/api/v1/rag/module/intent?question=${encodeURIComponent(question)}`,
      { timeout: AI_REQUEST_TIMEOUT_MS },
    );
  },

  testPrompt(question: string) {
    return request.get<string>(
      `/api/v1/rag/module/prompt?question=${encodeURIComponent(question)}`,
      { timeout: AI_REQUEST_TIMEOUT_MS },
    );
  },

  testRewrite(question: string) {
    return request.get<string[]>(
      `/api/v1/rag/module/rewrite?question=${encodeURIComponent(question)}`,
      { timeout: AI_REQUEST_TIMEOUT_MS },
    );
  },

  testRerank(question?: string) {
    const qs = question ? `?question=${encodeURIComponent(question)}` : '';
    return request.get<string>(`/api/v1/rag/module/rerank${qs}`, {
      timeout: AI_REQUEST_TIMEOUT_MS,
    });
  },
};
