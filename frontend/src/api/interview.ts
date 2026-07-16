import { AI_REQUEST_TIMEOUT_MS, request } from './request';
import type {
  AgentPlanProgress,
  AgentTraceGroup,
  CandidateMemoryProfile,
  CreateInterviewRequest,
  CurrentQuestionResponse,
  InterviewReport,
  InterviewSession,
  SubmitAnswerRequest,
  SubmitAnswerResponse
} from '../types/interview';
import type {EvaluateStatus} from './history';

export interface TextSessionMeta {
  sessionId: string;
  skillId: string;
  difficulty: string;
  resumeId: number | null;
  totalQuestions: number;
  status: string;
  evaluateStatus: EvaluateStatus | null;
  evaluateError: string | null;
  overallScore: number | null;
  jobInterview: boolean;
  jobDescriptionId: number | null;
  currentStage: string | null;
  sessionVersion: number | null;
  createdAt: string;
  completedAt: string | null;
}

export const interviewApi = {
  /**
   * 列出所有文字面试会话
   */
  async listSessions(): Promise<TextSessionMeta[]> {
    return request.get<TextSessionMeta[]>('/api/interview/sessions');
  },

  /**
   * 创建面试会话
   */
  async createSession(req: CreateInterviewRequest): Promise<InterviewSession> {
    return request.post<InterviewSession>('/api/interview/sessions', req, {
      timeout: AI_REQUEST_TIMEOUT_MS, // 3分钟超时，AI生成问题需要时间
    });
  },

  /**
   * 获取会话信息
   */
  async getSession(sessionId: string): Promise<InterviewSession> {
    return request.get<InterviewSession>(`/api/interview/sessions/${sessionId}`);
  },

  /**
   * 获取当前问题
   */
  async getCurrentQuestion(sessionId: string): Promise<CurrentQuestionResponse> {
    return request.get<CurrentQuestionResponse>(`/api/interview/sessions/${sessionId}/question`);
  },

  /**
   * 提交答案
   */
  async submitAnswer(req: SubmitAnswerRequest): Promise<SubmitAnswerResponse> {
    return request.post<SubmitAnswerResponse>(
      `/api/interview/sessions/${req.sessionId}/answers`,
      { questionIndex: req.questionIndex, answer: req.answer },
      {
        timeout: AI_REQUEST_TIMEOUT_MS, // 3分钟超时
      }
    );
  },

  /**
   * 获取面试报告
   */
  async getReport(sessionId: string): Promise<InterviewReport> {
    return request.get<InterviewReport>(`/api/interview/sessions/${sessionId}/report`, {
      timeout: AI_REQUEST_TIMEOUT_MS, // 3分钟超时，AI评估需要时间
    });
  },

  /**
   * 查找未完成的面试会话
   */
  async findUnfinishedSession(resumeId: number): Promise<InterviewSession | null> {
    try {
      return await request.get<InterviewSession>(`/api/interview/sessions/unfinished/${resumeId}`);
    } catch {
      // 如果没有未完成的会话，返回null
      return null;
    }
  },

  /**
   * 暂存答案（不进入下一题）
   */
  async saveAnswer(req: SubmitAnswerRequest): Promise<void> {
    return request.put<void>(
      `/api/interview/sessions/${req.sessionId}/answers`,
      { questionIndex: req.questionIndex, answer: req.answer }
    );
  },

  /**
   * 提前交卷
   */
  async completeInterview(sessionId: string): Promise<void> {
    return request.post<void>(`/api/interview/sessions/${sessionId}/complete`);
  },

  /**
   * 获取会话的面试大纲与进度（Multi-Agent 侧栏进度条）
   */
  async getAgentPlan(sessionId: string): Promise<AgentPlanProgress> {
    return request.get<AgentPlanProgress>(`/api/interview/sessions/${sessionId}/agent-plan`);
  },

  /**
   * 获取会话的 Multi-Agent 决策轨迹（Planner→Interviewer→Critic→Reflexion，按题号分组）
   */
  async getAgentTrace(sessionId: string): Promise<AgentTraceGroup[]> {
    return request.get<AgentTraceGroup[]>(`/api/interview/sessions/${sessionId}/agent-trace`);
  },

  /**
   * 获取当前用户候选人画像（按 topic 聚合的历史薄弱点/掌握点），skillId 可选
   */
  async getCandidateProfile(skillId?: string): Promise<CandidateMemoryProfile[]> {
    const qs = skillId ? `?skillId=${encodeURIComponent(skillId)}` : '';
    return request.get<CandidateMemoryProfile[]>(`/api/interview/candidate-memory/profile${qs}`);
  },
};
