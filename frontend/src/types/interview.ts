// 面试相关类型定义

export type Difficulty = 'junior' | 'mid' | 'senior';

/** 仅用于兼容历史文字面试快照；新会话使用版本化能力目录。 */
export interface CategoryDTO {
  key: string;
  label: string;
  priority: 'CORE' | 'NORMAL' | 'ALWAYS_ONE';
  ref?: string;
  shared?: boolean;
}

export interface InterviewSession {
  sessionId: string;
  resumeText: string;
  totalQuestions: number;
  currentQuestionIndex: number;
  questions: InterviewQuestion[];
  status: 'CREATED' | 'IN_PROGRESS' | 'COMPLETED' | 'EVALUATED';
  sessionVersion: number;
  createdAt?: string | null;
}

export interface InterviewQuestion {
  questionIndex: number;
  question: string;
  type: string;
  category: string;
  topicSummary?: string | null;
  userAnswer: string | null;
  score: number | null;
  feedback: string | null;
  isFollowUp?: boolean;
  parentQuestionIndex?: number | null;
  capabilityAtomId?: string | null;
  followUpAction?: 'DEEPEN' | 'CLARIFY' | 'REMEDIATE' | 'SWITCH_TOPIC' | null;
  evidenceIds?: string[];
}

export interface InterviewMessage {
  type: 'interviewer' | 'user';
  content: string;
  category?: string;
}

export interface CreateInterviewRequest {
  resumeText: string;
  questionCount: number;
  resumeId?: number;
  forceCreate?: boolean;
  llmProvider?: string;
  skillId: string;
  difficulty?: string;
  customCategories?: CategoryDTO[];
  jdText?: string;
  knowledgeBaseIds?: number[];
}

export interface SubmitAnswerRequest {
  sessionId: string;
  commandId?: string;
  expectedSessionVersion?: number;
  questionIndex: number;
  answer: string;
}

export interface SubmitAnswerResponse {
  hasNextQuestion: boolean;
  nextQuestion: InterviewQuestion | null;
  currentIndex: number;
  totalQuestions: number;
  sessionVersion: number;
}

export interface CurrentQuestionResponse {
  completed: boolean;
  question?: InterviewQuestion;
  message?: string;
}

export interface InterviewReport {
  sessionId: string;
  totalQuestions: number;
  overallScore: number;
  categoryScores: CategoryScore[];
  questionDetails: QuestionEvaluation[];
  overallFeedback: string;
  strengths: string[];
  improvements: string[];
  referenceAnswers: ReferenceAnswer[];
}

export interface CategoryScore {
  category: string;
  score: number;
  questionCount: number;
}

export interface QuestionEvaluation {
  questionIndex: number;
  question: string;
  category: string;
  userAnswer: string;
  score: number;
  feedback: string;
}

export interface ReferenceAnswer {
  questionIndex: number;
  question: string;
  referenceAnswer: string;
  keyPoints: string[];
}

// ============ Multi-Agent 编排（大纲 / 决策轨迹 / 候选人画像） ============

export interface AgentPlanTopic {
  name: string;
  focus: string;
  questionCount: number;
}

export interface AgentInterviewPlan {
  topics: AgentPlanTopic[];
  difficultyCurve: string;
  focusFromResume: string[];
  focusFromJd: string[];
}

export interface AgentPlanProgress {
  agentMode: boolean;
  currentIndex: number;
  plannedTotal: number;
  plan: AgentInterviewPlan | null;
}

export interface AgentTraceStep {
  step: number;
  role: string;
  action: string;
  actionInput: string;
  observation: string;
}

export interface AgentTraceGroup {
  questionIndex: number | null;
  steps: AgentTraceStep[];
}

export interface AgentTraceCatalogItem {
  sessionId: string;
  label: string;
  status: string | null;
  totalQuestions: number;
  orphanRun: boolean;
  hasPlan: boolean;
  stepCount: number;
  lastState: string | null;
  createdAt: string | null;
}

export interface AgentTraceEvent {
  step: number;
  questionIndex: number | null;
  role: string;
  action: string;
  state: string;
  headline: string;
  body: string;
  approved: boolean | null;
  score: number | null;
  retryHint: string | null;
  followUpAction: string | null;
  capability: string | null;
  evidenceIds: string[];
  reflexion: boolean;
  input?: string | null;
}

export interface AgentTraceAct {
  questionIndex: number | null;
  title: string;
  statePath: string[];
  reflexionRounds: number;
  finalQuestion: string | null;
  followUpAction: string | null;
  criticApproved: boolean | null;
  events: AgentTraceEvent[];
}

export interface AgentTracePlayback {
  sessionId: string;
  sourceIds: string[];
  agentMode: boolean;
  stepCount: number;
  reflexionRounds: number;
  criticRejects: number;
  groundingRejects: number;
  toolCalls: number;
  emptyReason: string | null;
  emptyHint: string | null;
  plan: AgentInterviewPlan | null;
  acts: AgentTraceAct[];
  spans?: AgentTraceSpan[];
}

export interface AgentTraceSpan {
  spanId: string;
  parentSpanId: string | null;
  kind: 'agent' | 'chat' | 'tool' | string;
  role: string | null;
  action: string | null;
  title: string;
  input: string | null;
  output: string | null;
  status: string | null;
  latencyMs: number | null;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  questionIndex: number | null;
  children: AgentTraceSpan[];
}

export interface CandidateMemoryProfile {
  capabilityAtomId: string | null;
  topic: string;
  averageScore: number | null;
  observationCount: number;
  sessionCount: number;
  masteryLevel: 'STRENGTH' | 'DEVELOPING' | 'WEAKNESS';
  verificationState: 'PROVISIONAL' | 'VERIFIED';
  confidenceLevel: 'LOW' | 'MEDIUM' | 'HIGH';
  weaknessCount: number;
  developingCount: number;
  strengthCount: number;
  latestKind: string;
  latestEvidence: string;
  latestEvidenceIds: string[];
  lastSessionId: string;
  lastAt: string;
}

export type MemoryMasteryLevel = 'STRENGTH' | 'DEVELOPING' | 'WEAKNESS';
export type MemoryVerificationState = 'PROVISIONAL' | 'VERIFIED';

export interface ShortTermMemoryTurn {
  role: 'USER' | 'ASSISTANT' | 'OTHER' | string;
  text: string;
}

export interface ShortTermMemory {
  sessionId: string | null;
  skillId: string | null;
  live: boolean;
  windowSize: number;
  agentMessageCount: number;
  turns: ShortTermMemoryTurn[];
}

export interface CompressedMemoryTurn {
  questionIndex: number;
  topic: string;
  followUpAction: string | null;
  meaningfulChars: number;
  hasReasoning: boolean;
  hasExample: boolean;
  hasTradeOff: boolean;
  expressesUncertainty: boolean;
}

export interface CompressedMemory {
  sessionId: string | null;
  skillId: string | null;
  turns: CompressedMemoryTurn[];
}

export interface LongTermMemoryItem {
  topic: string;
  capabilityAtomId: string | null;
  masteryLevel: MemoryMasteryLevel;
  verificationState: MemoryVerificationState;
  averageScore: number | null;
  observationCount: number;
  sessionCount: number;
  latestEvidence: string | null;
  lastAt: string | null;
}

export interface InterviewMemory {
  shortTerm: ShortTermMemory;
  compressed: CompressedMemory;
  longTerm: LongTermMemoryItem[];
}
