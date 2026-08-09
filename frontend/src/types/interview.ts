// 面试相关类型定义

export type Difficulty = 'junior' | 'mid' | 'senior';

/** 仅用于兼容历史文字面试快照；新岗位实战使用版本化能力目录。 */
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
