import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate, useOutletContext, useParams } from 'react-router-dom';
import Layout from './components/Layout';
import { useEffect, useState, Suspense, lazy } from 'react';
import { historyApi, type InterviewDetail } from './api/history';
import { interviewApi } from './api/interview';
import { getErrorMessage } from './api/request';
import type { UploadKnowledgeBaseResponse } from './api/knowledgebase';
import type { CategoryDTO, Difficulty } from './types/interview';
import type { InterviewSetupResult } from './pages/InterviewSetupPage';
import { ErrorState, LoadingState } from './components/PageState';
import { ROUTES } from './app/routes';
import { ArrowLeft } from 'lucide-react';
import RequireAuth from './components/RequireAuth';
import {getInterviewViewPath} from './utils/interviewNavigation';

// Lazy load components
const UploadPage = lazy(() => import('./pages/UploadPage'));
const HistoryPage = lazy(() => import('./pages/HistoryPage'));
const ResumeDetailPage = lazy(() => import('./pages/ResumeDetailPage'));
const Interview = lazy(() => import('./pages/InterviewPage'));
const InterviewSetupPage = lazy(() => import('./pages/InterviewSetupPage'));
const InterviewHistoryPage = lazy(() => import('./pages/InterviewHistoryPage'));
const KnowledgeBaseQueryPage = lazy(() => import('./pages/KnowledgeBaseQueryPage'));
const KnowledgeBaseUploadPage = lazy(() => import('./pages/KnowledgeBaseUploadPage'));
const KnowledgeBaseManagePage = lazy(() => import('./pages/KnowledgeBaseManagePage'));
const InterviewSchedulePage = lazy(() => import('./pages/InterviewSchedulePage'));
const EvalRunPage = lazy(() => import('./pages/EvalRunPage'));
const AgentTracePage = lazy(() => import('./pages/AgentTracePage'));
const RagTracePage = lazy(() => import('./pages/RagTracePage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const InterviewDetailPanel = lazy(() => import('./components/InterviewDetailPanel'));
const LoginPage = lazy(() => import('./pages/LoginPage'));
const DashboardPage = lazy(() => import('./pages/DashboardPage'));
const RecruitmentRadarPage = lazy(() => import('./pages/RecruitmentRadarPage'));
const CareerResourcesPage = lazy(() => import('./pages/CareerResourcesPage'));
const ProfilePage = lazy(() => import('./pages/ProfilePage'));

function NotFoundPage() {
  const navigate = useNavigate();
  return (
    <ErrorState
      title="页面不存在"
      description="这个地址可能已失效，或对应功能已经调整。"
      action={<button onClick={() => navigate('/dashboard')} className="btn-primary px-4 py-2 text-sm">返回工作台</button>}
    />
  );
}

// Loading component
const Loading = () => (
  <LoadingState
    className="flex items-center justify-center min-h-[50vh]"
    spinnerClassName="w-10 h-10 text-primary-500 animate-spin"
  />
);

// 上传页面包装器
function UploadPageWrapper() {
  const navigate = useNavigate();

  const handleUploadComplete = (resumeId: number) => {
    // 异步模式：上传成功后跳转到简历库，让用户在列表中查看分析状态
    navigate('/history', { state: { newResumeId: resumeId } });
  };

  return <UploadPage onUploadComplete={handleUploadComplete} />;
}

// 历史记录页包装器
function HistoryPageWrapper() {
  const navigate = useNavigate();

  const handleSelectResume = (id: number) => {
    navigate(`/history/${id}`);
  };

  return <HistoryPage onSelectResume={handleSelectResume} />;
}

// 简历详情包装器
function ResumeDetailWrapper() {
  const { resumeId } = useParams<{ resumeId: string }>();
  const navigate = useNavigate();
  const { openInterviewModalWithResume } = useOutletContext<{ openInterviewModalWithResume: (resumeId: number) => void }>();

  if (!resumeId) {
    return <Navigate to="/history" replace />;
  }

  const handleBack = () => {
    navigate('/history');
  };

  const handleStartInterview = (id: number) => {
    openInterviewModalWithResume(id);
  };

  return (
    <ResumeDetailPage
      resumeId={parseInt(resumeId, 10)}
      onBack={handleBack}
      onStartInterview={handleStartInterview}
    />
  );
}

interface InterviewEntryState {
  resumeId?: number;
  resumeText?: string;
  sessionIdToResume?: string;
  interviewConfig?: {
    skillId?: string;
    difficulty?: Difficulty;
    questionCount?: number;
    llmProvider?: string;
    customCategories?: CategoryDTO[];
    jdText?: string;
    knowledgeBaseIds?: number[];
  };
}

// 模拟面试包装器
function InterviewWrapper() {
  const { resumeId } = useParams<{ resumeId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const entryState = (location.state as InterviewEntryState | undefined) ?? {};
  const presetResumeId = resumeId ? parseInt(resumeId, 10) : entryState.resumeId;
  const [prepared, setPrepared] = useState<InterviewSetupResult | null>(null);

  const handleBack = () => {
    if (prepared) {
      setPrepared(null);
      return;
    }
    if (presetResumeId) {
      navigate(`/history/${presetResumeId}`, { replace: false });
      return;
    }
    navigate('/dashboard', { replace: false });
  };

  const handleInterviewComplete = () => {
    navigate('/interviews');
  };

  if (entryState.sessionIdToResume) {
    return (
      <Interview
        resumeText={entryState.resumeText ?? ''}
        resumeId={presetResumeId}
        sessionIdToResume={entryState.sessionIdToResume}
        initialConfig={entryState.interviewConfig}
        autoStart
        onBack={handleBack}
        onInterviewComplete={handleInterviewComplete}
      />
    );
  }

  if (!prepared) {
    return (
      <InterviewSetupPage
        initialResumeId={presetResumeId}
        onStart={setPrepared}
      />
    );
  }

  return (
    <Interview
      resumeText={prepared.resumeText}
      resumeId={prepared.resumeId}
      initialConfig={{
        skillId: prepared.skillId,
        questionCount: prepared.questionCount,
        jdText: prepared.jdText,
        knowledgeBaseIds: prepared.knowledgeBaseIds,
      }}
      autoStart
      onBack={handleBack}
      onInterviewComplete={handleInterviewComplete}
    />
  );
}

function App() {
  return (
    <BrowserRouter>
      <Suspense fallback={<Loading />}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route element={<RequireAuth />}>
            <Route path="/" element={<Layout />}>
              <Route index element={<Navigate to="/dashboard" replace />} />

              {/* 面向求职者的一级入口 */}
              <Route path="dashboard" element={<DashboardPage />} />
              <Route path="recruitment" element={<RecruitmentRadarPage />} />
              <Route path="resources" element={<CareerResourcesPage />} />
              <Route path="profile" element={<ProfilePage />} />

              {/* 上传页面 */}
              <Route path="upload" element={<UploadPageWrapper />} />

              {/* 历史记录列表（简历库） */}
              <Route path="history" element={<HistoryPageWrapper />} />

              {/* 简历详情 */}
              <Route path="history/:resumeId" element={<ResumeDetailWrapper />} />

              {/* 面试中心 */}
              <Route path="interview-hub" element={<Navigate to="/interview" replace />} />
              <Route path="job-practice/*" element={<Navigate to="/interview" replace />} />

              {/* 面试记录列表 */}
              <Route path="interviews" element={<InterviewHistoryWrapper />} />

              {/* 面试详情报告 */}
              <Route path="interviews/:sessionId" element={<InterviewDetailPageWrapper />} />

              {/* 模拟面试（通用入口） */}
              <Route path="interview" element={<InterviewWrapper />} />

              {/* 模拟面试 */}
              <Route path="interview/:resumeId" element={<InterviewWrapper />} />

              {/* 已下线入口：旧链接安全回到文字面试中心 */}
              <Route path="voice-interview/*" element={<Navigate to="/interview-hub" replace />} />

              {/* 知识库管理 */}
              <Route path="knowledgebase" element={<KnowledgeBaseManagePageWrapper />} />

              {/* 知识库上传 */}
              <Route path="knowledgebase/upload" element={<KnowledgeBaseUploadPageWrapper />} />

              {/* 面试日程管理 */}
              <Route path="interview-schedule" element={<InterviewSchedulePage />} />

              {/* 设置 */}
              <Route path="settings" element={<SettingsPage />} />

              {/* 问答助手（知识库聊天） */}
              <Route path="knowledgebase/chat" element={<KnowledgeBaseQueryPageWrapper />} />

              {/* RAG 效果评测 */}
              <Route path="eval" element={<EvalRunPage />} />

              {/* Multi-Agent 编排 Trace */}
              <Route path="agent-trace" element={<AgentTracePage />} />

              {/* 阶段化 RAG Trace */}
              <Route path="rag-traces" element={<RagTracePage />} />

              {/* 已下线入口：旧链接安全回到 RAG 问答 */}
              <Route path="knowledge-graph" element={<Navigate to="/knowledgebase/chat" replace />} />
              <Route path="*" element={<NotFoundPage />} />
            </Route>
          </Route>
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}

// 面试记录页面包装器
function InterviewHistoryWrapper() {
  const navigate = useNavigate();
  const { openInterviewModalWithResume } = useOutletContext<{ openInterviewModalWithResume: (resumeId: number) => void }>();

  const handleBack = () => {
    navigate('/history');
  };

  const handleViewInterview = async (sessionId: string) => {
    navigate(getInterviewViewPath(sessionId));
  };

  const handleRestartInterview = (resumeId: number) => {
    openInterviewModalWithResume(resumeId);
  };

  const handleContinueInterview = (sessionId: string) => {
    navigate('/interview', { state: { sessionIdToResume: sessionId } });
  };

  return <InterviewHistoryPage onBack={handleBack} onViewInterview={handleViewInterview} onRestartInterview={handleRestartInterview} onContinueInterview={handleContinueInterview} />;
}

// 面试详情报告页面包装器
function InterviewDetailPageWrapper() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const [interview, setInterview] = useState<InterviewDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reevaluating, setReevaluating] = useState(false);

  useEffect(() => {
    if (!sessionId) {
      navigate('/interviews');
      return;
    }
    historyApi.getInterviewDetail(sessionId)
      .then(detail => {
        setInterview(detail);
        setLoading(false);
      })
      .catch(() => {
        setError('加载面试详情失败');
        setLoading(false);
      });
  }, [sessionId, navigate]);

  if (loading) {
    return (
      <LoadingState className="flex items-center justify-center min-h-[50vh]" />
    );
  }

  if (error || !interview || !sessionId) {
    return (
      <ErrorState
        title={error || '面试记录不存在'}
        action={
          <button
            onClick={() => navigate('/interviews')}
            className="btn-primary px-5 py-2.5 text-sm"
          >
            返回面试记录
          </button>
        }
      />
    );
  }

  return (
    <div className="mx-auto max-w-4xl space-y-5">
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('/interviews')}
          aria-label="返回面试记录"
          className="rounded-lg p-2 text-stone-500 transition-colors hover:bg-stone-100 hover:text-stone-900 dark:hover:bg-stone-800 dark:hover:text-white"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-stone-950 dark:text-white">面试复盘</h1>
          <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">回看每道题的回答和改进建议。</p>
        </div>
      </div>
      <InterviewDetailPanel
        interview={interview}
        reevaluating={reevaluating}
        onReevaluate={async () => {
          if (!sessionId) return;
          setReevaluating(true);
          try {
            await interviewApi.reevaluateInterview(sessionId);
            const detail = await historyApi.getInterviewDetail(sessionId);
            setInterview(detail);
          } catch (err) {
            alert(getErrorMessage(err, '重新评估失败，请稍后重试'));
          } finally {
            setReevaluating(false);
          }
        }}
      />
    </div>
  );
}
function KnowledgeBaseManagePageWrapper() {
  const navigate = useNavigate();

  const handleUpload = () => {
    navigate(ROUTES.knowledgebaseUpload);
  };

  const handleChat = () => {
    navigate('/knowledgebase/chat');
  };

  return <KnowledgeBaseManagePage onUpload={handleUpload} onChat={handleChat} />;
}

// 知识库问答页面包装器
function KnowledgeBaseQueryPageWrapper() {
  const navigate = useNavigate();
  const location = useLocation();
  const isChatMode = location.pathname === '/knowledgebase/chat';

  const handleBack = () => {
    if (isChatMode) {
      navigate('/knowledgebase');
    } else {
      navigate('/history');
    }
  };

  const handleUpload = () => {
    navigate(ROUTES.knowledgebaseUpload);
  };

  return <KnowledgeBaseQueryPage onBack={handleBack} onUpload={handleUpload} />;
}

// 知识库上传页面包装器
function KnowledgeBaseUploadPageWrapper() {
  const navigate = useNavigate();

  const handleUploadComplete = (_result: UploadKnowledgeBaseResponse) => {
    // 上传完成后返回管理页面
    navigate('/knowledgebase');
  };

  const handleBack = () => {
    navigate('/knowledgebase');
  };

  return <KnowledgeBaseUploadPage onUploadComplete={handleUploadComplete} onBack={handleBack} />;
}

export default App;
