import { AI_REQUEST_TIMEOUT_MS, request } from './request';

export interface RagIntentEntities {
  skill: string | null;
  resumeId: number | null;
  sessionId: number | null;
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
      `/api/rag/module/intent?question=${encodeURIComponent(question)}`,
      { timeout: AI_REQUEST_TIMEOUT_MS },
    );
  },

  testPrompt(question: string) {
    return request.get<string>(
      `/api/rag/module/prompt?question=${encodeURIComponent(question)}`,
      { timeout: AI_REQUEST_TIMEOUT_MS },
    );
  },

  testRewrite(question: string) {
    return request.get<string[]>(
      `/api/rag/module/rewrite?question=${encodeURIComponent(question)}`,
      { timeout: AI_REQUEST_TIMEOUT_MS },
    );
  },

  testRerank(question?: string) {
    const qs = question ? `?question=${encodeURIComponent(question)}` : '';
    return request.get<string>(`/api/rag/module/rerank${qs}`, {
      timeout: AI_REQUEST_TIMEOUT_MS,
    });
  },
};
