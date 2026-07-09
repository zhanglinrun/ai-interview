import { AI_REQUEST_TIMEOUT_MS, request } from './request';

export interface IntentCase {
  question: string;
  expectedIntent?: string;
  expectedRelated?: boolean;
}

export interface JudgeCase {
  question: string;
  answer: string;
  referenceAnswer?: string;
  context?: string;
  minOverallScore?: number;
}

export interface RagEvalItem {
  question: string;
  expectedKeywords?: string[];
  expectedChunkIds?: string[];
}

export interface RagEvalRequestBody {
  knowledgeBaseIds: number[];
  items: RagEvalItem[];
  k?: number;
  title?: string;
}

export interface EvalRunRequest {
  title?: string;
  baselineKey?: string;
  updateBaseline?: boolean;
  regressionThreshold?: number;
  intentCases?: IntentCase[];
  rag?: RagEvalRequestBody;
  judgeCases?: JudgeCase[];
}

export interface IntentItemResult {
  question: string;
  expectedIntent: string | null;
  expectedRelated: boolean | null;
  actualIntent: string;
  actualRelated: boolean;
  confidence: number;
  correct: boolean;
  reason: string;
}

export interface IntentEvaluationResult {
  total: number;
  correct: number;
  accuracy: number;
  macroF1: number;
  items: IntentItemResult[];
}

export interface RagItemResult {
  question: string;
  hit: boolean;
  firstHitRank: number;
  reciprocalRank: number;
  ndcg: number;
}

export interface RagEvalResponse {
  runId: string | null;
  total: number;
  k: number;
  hitRate: number;
  mrr: number;
  ndcg: number;
  citationHitRate: number;
  citationCoverage: number;
  items: RagItemResult[];
}

export interface JudgeItemResult {
  question: string;
  minOverallScore: number;
  passed: boolean;
  relevance: number;
  accuracy: number;
  completeness: number;
  helpfulness: number;
  overall: number;
  reason: string;
  improvement: string;
}

export interface JudgeEvaluationResult {
  total: number;
  passed: number;
  passRate: number;
  averageOverall: number;
  averageRelevance: number;
  averageAccuracy: number;
  averageCompleteness: number;
  averageHelpfulness: number;
  items: JudgeItemResult[];
}

export interface MetricDelta {
  metric: string;
  current: number;
  baseline: number;
  delta: number;
  regressed: boolean;
}

export interface BaselineComparison {
  baselineRunId: string;
  baselineCreatedAt: string;
  threshold: number;
  metrics: MetricDelta[];
}

export interface EvalRunResponse {
  runId: string;
  title: string;
  baselineKey: string | null;
  baseline: boolean;
  overallScore: number;
  regression: boolean;
  intent: IntentEvaluationResult | null;
  rag: RagEvalResponse | null;
  judge: JudgeEvaluationResult | null;
  baselineComparison: BaselineComparison | null;
  createdAt: string;
}

export const evalApi = {
  /**
   * 运行统一评测闭环（意图识别 + RAG 检索 + LLM-as-Judge + 基线回归）。
   * 后端会跑真实 LLM/检索链路，耗时较长，故沿用 AI 请求超时。
   */
  run(body: EvalRunRequest): Promise<EvalRunResponse> {
    return request.post<EvalRunResponse>('/api/eval/run', body, {
      timeout: AI_REQUEST_TIMEOUT_MS,
    });
  },
};
