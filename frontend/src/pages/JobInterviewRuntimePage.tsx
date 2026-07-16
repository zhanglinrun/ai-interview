import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  Circle,
  Clock3,
  Code2,
  FileText,
  HelpCircle,
  Loader2,
  PauseCircle,
  Radio,
  Save,
  Send,
  StopCircle,
} from 'lucide-react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  jobInterviewApi,
  subscribeJobInterviewEvents,
  type CommandResult,
  type JobInterviewAssessment,
  type JobInterviewSession,
  type JobInterviewStage,
} from '../api/jobInterview';
import { getErrorMessage } from '../api/request';
import CodeEditor from '../components/CodeEditor';
import { ErrorState, LoadingState } from '../components/PageState';
import PageHeader from '../components/ui/PageHeader';

const stages: Array<{ stage: JobInterviewStage; label: string; description: string }> = [
  { stage: 'PROJECT_DEEP_DIVE', label: '项目经历', description: '背景、职责和实现细节' },
  { stage: 'POSITION_TECH', label: '岗位技术', description: '结合岗位继续追问' },
  { stage: 'ENGINEERING_SCENARIO', label: '工程场景', description: '排障和设计取舍' },
  { stage: 'ALGORITHM', label: '算法题', description: '说明思路并完成代码' },
];

const statusLabel: Record<JobInterviewSession['status'], string> = {
  READY: '待开始',
  IN_PROGRESS: '进行中',
  PAUSED: '已暂停',
  COMPLETING: '报告生成中',
  COMPLETED: '已完成',
  ABORTED: '已中止',
  FAILED: '异常结束',
};

const judgeStatusLabel: Record<string, string> = {
  DRAFT: '草稿已保存',
  QUEUED: '排队中',
  PROCESSING: '判题中',
  ACCEPTED: '通过',
  WRONG_ANSWER: '答案错误',
  COMPILE_ERROR: '编译错误',
  RUNTIME_ERROR: '运行错误',
  TIME_LIMIT_EXCEEDED: '超出时间限制',
  MEMORY_LIMIT_EXCEEDED: '超出内存限制',
  INTERNAL_ERROR: '判题服务异常',
  UNAVAILABLE: '暂未判题',
};

const degradedReasonLabel: Record<string, string> = {
  RESUME_UNAVAILABLE: '简历暂不可用',
  PERSONAL_KNOWLEDGE_UNAVAILABLE: '个人知识资料暂不可用',
  GITHUB_BINDING_UNAVAILABLE: 'GitHub 项目绑定暂不可用',
  GITHUB_EVIDENCE_UNAVAILABLE: 'GitHub 项目内容暂不可用',
  EVIDENCE_RETRIEVAL_UNAVAILABLE: '资料检索暂不可用',
  QUERY_DECOMPOSITION_UNAVAILABLE: '复杂问题拆分暂不可用',
  CRAG_UNAVAILABLE: '检索纠错暂不可用',
  CRAG_CORRECTED_ONCE: '资料检索已自动调整',
  RERANK_UNAVAILABLE: '结果重排暂不可用',
  RERANK_INVALID_RESPONSE: '结果重排返回异常',
  SOURCE_DELETED_UNVERIFIABLE: '参考资料已删除，暂时无法核验',
  JUDGE0_NOT_CONFIGURED: '算法代码会保存，当前暂不自动判题',
  ALGORITHM_SERVICE_UNAVAILABLE: '算法题服务暂不可用',
  HOT100_CONTENT_UNAVAILABLE: 'Hot 100 题目暂不可用',
  ALGORITHM_SUBMISSION_UNAVAILABLE: '在线判题暂不可用，代码已保存',
  JUDGE_UNAVAILABLE: '在线判题暂不可用',
  AI_EVALUATION_UNAVAILABLE: '回答评估暂不可用',
  AI_CLARIFICATION_UNAVAILABLE: '题意澄清暂不可用',
};

function judgeStatusText(value: string): string {
  return judgeStatusLabel[value] || '判题状态待确认';
}

function degradedReasonText(value: string): string {
  return degradedReasonLabel[value]
    || (/^[A-Z0-9_]+$/.test(value) ? '相关服务暂不可用' : value);
}

function scoreText(value?: number | null): string {
  return value == null ? '未提供' : `${value}`;
}

function evidenceStatusText(value?: string | null): string {
  if (!value || value === 'NONE') return '未使用资料';
  if (value === 'SUFFICIENT') return '资料佐证充分';
  if (value === 'WEAK') return '资料佐证有限';
  if (value === 'CONFLICT') return '与资料有出入';
  if (value === 'SUPPORTED') return '有资料支持';
  if (value === 'CONFLICTED') return '与资料有出入';
  if (value === 'UNAVAILABLE' || value === 'SOURCE_UNAVAILABLE') return '缺少资料佐证';
  return '资料状态未知';
}

export default function JobInterviewRuntimePage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const [session, setSession] = useState<JobInterviewSession | null>(null);
  const [assessment, setAssessment] = useState<JobInterviewAssessment | null>(null);
  const [answer, setAnswer] = useState('');
  const [clarification, setClarification] = useState('');
  const [sourceCode, setSourceCode] = useState('');
  const [functionSignature, setFunctionSignature] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [connected, setConnected] = useState(false);
  const [now, setNow] = useState(Date.now());
  const lastEventId = useRef(0);
  const lastQuestionId = useRef<number | null>(null);

  const refreshSession = useCallback(async () => {
    if (!sessionId) return null;
    const loaded = await jobInterviewApi.getSession(sessionId);
    setSession(loaded);
    return loaded;
  }, [sessionId]);

  useEffect(() => {
    let active = true;
    if (!sessionId) {
      setError('面试会话参数缺失');
      setLoading(false);
      return;
    }
    refreshSession()
      .catch((reason) => {
        if (active) setError(getErrorMessage(reason, '面试会话加载失败'));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [refreshSession, sessionId]);

  useEffect(() => {
    if (!sessionId || !session || ['COMPLETED', 'ABORTED', 'FAILED'].includes(session.status)) {
      setConnected(false);
      return;
    }
    return subscribeJobInterviewEvents(
      sessionId,
      lastEventId.current,
      (event) => {
        lastEventId.current = Math.max(lastEventId.current, event.eventId);
        void refreshSession();
      },
      setConnected,
    );
  }, [refreshSession, session?.status, sessionId]);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    const question = session?.currentQuestion;
    if (!question || question.questionId === lastQuestionId.current) return;
    lastQuestionId.current = question.questionId;
    setAnswer('');
    setClarification('');
    setAssessment(null);
    if (question.stage === 'ALGORITHM') {
      setFunctionSignature('');
      setSourceCode(session?.codingLanguage === 'JAVA21'
        ? '// 请按题目给出的函数签名完成 Java 代码\n'
        : '# 请按题目给出的函数签名完成 Python 3 代码\n');
      if (sessionId) {
        jobInterviewApi.getCodeDraft(sessionId, question.questionId)
          .then((draft) => {
            if (draft && lastQuestionId.current === question.questionId) {
              setSourceCode(draft.sourceCode);
              setFunctionSignature(draft.functionSignature);
              if (draft.judgeStatus && draft.judgeStatus !== 'INITIAL') {
                setNotice(`已恢复代码草稿 · 最近判题状态：${judgeStatusText(draft.judgeStatus)}`);
              }
            }
          })
          .catch(() => {
            // 首次进入算法题没有草稿是正常路径，继续使用空编辑器。
          });
      }
    } else {
      setSourceCode('');
      setFunctionSignature('');
    }
  }, [session?.codingLanguage, session?.currentQuestion, sessionId]);

  const currentStageIndex = useMemo(
    () => Math.max(0, stages.findIndex((item) => item.stage === session?.stage)),
    [session?.stage],
  );

  const remainingSeconds = useMemo(() => {
    if (!session?.stageDeadlineAt) return null;
    return Math.max(0, Math.ceil((new Date(session.stageDeadlineAt).getTime() - now) / 1_000));
  }, [now, session?.stageDeadlineAt]);

  const applyCommand = async (
    key: string,
    action: () => Promise<CommandResult>,
    successMessage: string,
  ) => {
    setBusy(key);
    setError('');
    setNotice('');
    try {
      const result = await action();
      if (result.eventId) lastEventId.current = Math.max(lastEventId.current, result.eventId);
      if (result.assessment) setAssessment(result.assessment);
      setNotice(result.duplicate
        ? '检测到重复指令，服务端已返回第一次执行结果，没有重复推进会话。'
        : result.message || successMessage);
      await refreshSession();
      return true;
    } catch (reason) {
      setError(getErrorMessage(reason, '指令执行失败'));
      try {
        await refreshSession();
      } catch {
        // 保留原始业务错误，用户可手动刷新页面恢复。
      }
      return false;
    } finally {
      setBusy('');
    }
  };

  if (loading) return <LoadingState label="正在恢复面试..." />;
  if (error && !session) {
    return <ErrorState title="无法加载面试" description={error} action={(
      <button onClick={() => navigate('/job-practice')} className="btn-secondary px-4 py-2 text-sm">返回岗位实战</button>
    )} />;
  }
  if (!session || !sessionId) return null;

  const question = session.currentQuestion;
  const terminal = ['COMPLETED', 'ABORTED', 'FAILED'].includes(session.status);

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader
        eyebrow={statusLabel[session.status]}
        title="岗位面试"
        description={`共 ${session.totalQuestions} 题，已作答 ${session.answeredQuestions} 题`}
        onBack={() => navigate('/job-practice')}
        action={(
          <div className={`inline-flex items-center gap-2 rounded-full px-3 py-1.5 text-xs font-medium ${connected ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300' : 'bg-stone-100 text-stone-500 dark:bg-stone-800 dark:text-stone-300'}`}>
            <Radio className={`h-3.5 w-3.5 ${connected ? 'animate-pulse' : ''}`} />
            {terminal ? '已结束' : connected ? '已连接' : '正在重新连接'}
          </div>
        )}
      />

      {error && (
        <div className="mb-4 flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-300">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />{error}
        </div>
      )}
      {notice && (
        <div className="mb-4 rounded-xl border border-primary-200 bg-primary-50 px-4 py-3 text-sm text-primary-800 dark:border-primary-900/60 dark:bg-primary-950/30 dark:text-primary-200">{notice}</div>
      )}
      {session.degradedReasons.length > 0 && (
        <div className="mb-4 flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-300">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <div><strong>本次面试说明：</strong>{session.degradedReasons.map(degradedReasonText).join('；')}。报告会保留这一说明。</div>
        </div>
      )}

      <section className="surface-card mb-5 p-5">
        <div className="grid gap-3 md:grid-cols-4">
          {stages.map((item, index) => {
            const active = item.stage === session.stage && !terminal;
            const completed = index < currentStageIndex || session.status === 'COMPLETED';
            return (
              <div key={item.stage} className={`rounded-lg border p-3 ${active ? 'border-primary-300 bg-primary-50 dark:border-primary-800 dark:bg-primary-950/30' : 'border-stone-200 dark:border-stone-800'}`}>
                <div className="flex items-center gap-2">
                  {completed
                    ? <CheckCircle2 className="h-4 w-4 text-emerald-600" />
                    : active
                      ? <Clock3 className="h-4 w-4 text-primary-600" />
                      : <Circle className="h-4 w-4 text-stone-300" />}
                  <span className="text-sm font-medium text-stone-900 dark:text-white">{item.label}</span>
                </div>
                <p className="mt-1 text-xs text-stone-400">{item.description}</p>
              </div>
            );
          })}
        </div>
        <div className="mt-4 flex flex-wrap items-center gap-4 text-xs text-stone-500 dark:text-stone-400">
          <span>作答进度 {session.answeredQuestions}/{session.totalQuestions}</span>
          {!terminal && remainingSeconds != null && <span className={remainingSeconds === 0 ? 'text-amber-600' : ''}>当前阶段剩余约 {Math.floor(remainingSeconds / 60)}:{String(remainingSeconds % 60).padStart(2, '0')}</span>}
        </div>
      </section>

      {session.status === 'READY' && (
        <section className="surface-card p-8 text-center">
          <FileText className="mx-auto h-10 w-10 text-primary-600" />
          <h2 className="mt-4 text-xl font-semibold text-stone-900 dark:text-white">面试已准备好</h2>
          <p className="mx-auto mt-2 max-w-xl text-sm leading-6 text-stone-500 dark:text-stone-400">面试分为四个部分。刷新页面不会丢失进度，24 小时内可以继续。</p>
          <button
            disabled={Boolean(busy)}
            onClick={() => applyCommand('start', () => jobInterviewApi.start(sessionId, session.sessionVersion), '面试已开始')}
            className="btn-primary mt-6 inline-flex items-center gap-2 px-5 py-3 text-sm disabled:opacity-60"
          >
            {busy === 'start' ? <Loader2 className="h-4 w-4 animate-spin" /> : <ArrowRight className="h-4 w-4" />}开始面试
          </button>
        </section>
      )}

      {session.status === 'PAUSED' && (
        <section className="surface-card p-8 text-center">
          <PauseCircle className="mx-auto h-10 w-10 text-amber-600" />
          <h2 className="mt-4 text-xl font-semibold text-stone-900 dark:text-white">会话已暂停</h2>
          <p className="mt-2 text-sm text-stone-500 dark:text-stone-400">
            {session.canResume
              ? `恢复有效期至 ${session.resumeExpiresAt ? new Date(session.resumeExpiresAt).toLocaleString() : '服务端设定时间'}。`
              : '本场已使用过一次恢复机会，可以提前交卷生成报告，或中止本次面试。'}
          </p>
          <div className="mt-5 flex flex-wrap justify-center gap-3">
            {session.canResume && (
              <button disabled={Boolean(busy)} onClick={() => applyCommand('continue', () => jobInterviewApi.continue(sessionId, session.sessionVersion), '会话已恢复')} className="btn-primary inline-flex items-center gap-2 px-5 py-2.5 text-sm disabled:opacity-60"><ArrowRight className="h-4 w-4" />继续面试</button>
            )}
            <button disabled={Boolean(busy)} onClick={() => applyCommand('finish', () => jobInterviewApi.finish(sessionId, session.sessionVersion), '正在生成面试报告')} className="btn-secondary inline-flex items-center gap-2 px-4 py-2.5 text-sm disabled:opacity-60"><CheckCircle2 className="h-4 w-4" />结束并生成报告</button>
            <button disabled={Boolean(busy)} onClick={() => {
              if (window.confirm('确认中止本次面试吗？中止记录不会更新能力画像。')) {
                void applyCommand('abort', () => jobInterviewApi.abort(sessionId, session.sessionVersion, '用户主动中止'), '面试已中止');
              }
            }} className="inline-flex items-center gap-2 rounded-xl px-4 py-2.5 text-sm text-red-600 hover:bg-red-50 disabled:opacity-60 dark:hover:bg-red-950/30"><StopCircle className="h-4 w-4" />中止</button>
          </div>
        </section>
      )}

      {session.status === 'IN_PROGRESS' && question && (
        <div className="grid gap-5 lg:grid-cols-[minmax(0,1.2fr)_minmax(300px,0.8fr)]">
          <section className="surface-card overflow-hidden">
            <div className="border-b border-stone-200/80 p-6 dark:border-stone-800">
              <div className="flex flex-wrap items-center gap-2 text-xs text-stone-400">
                <span>{question.followUp ? '针对上一题的追问' : `第 ${question.questionIndex + 1} 题`}</span>
                <span>·</span><span>{stages.find((item) => item.stage === question.stage)?.label}</span>
              </div>
              <h2 className="mt-4 whitespace-pre-wrap text-xl font-semibold leading-8 text-stone-900 dark:text-white">{question.question}</h2>
              <p className="mt-3 text-xs text-stone-400">建议作答时间 {Math.ceil(question.budgetSeconds / 60)} 分钟。像真实面试一样，先说结论，再结合经历展开。</p>
            </div>

            {question.stage === 'ALGORITHM' ? (
              <div>
                <div className="flex flex-wrap items-center justify-between gap-2 bg-stone-950 px-4 py-2 text-xs text-stone-400"><span>{session.codingLanguage === 'JAVA21' ? 'Java' : 'Python 3'}{functionSignature ? ` · ${functionSignature}` : ''}</span><span>提交后运行测试用例</span></div>
                <CodeEditor
                  value={sourceCode}
                  onChange={setSourceCode}
                  language={session.codingLanguage === 'JAVA21' ? 'java' : 'python'}
                  ariaLabel="岗位算法代码编辑器"
                  height={430}
                />
                <div className="flex flex-wrap gap-2 border-t border-stone-200/80 p-4 dark:border-stone-800">
                  <button disabled={Boolean(busy) || !sourceCode.trim()} onClick={() => applyCommand('save-code', () => jobInterviewApi.saveCode(sessionId, session.sessionVersion, question.questionId, sourceCode), '代码草稿已保存')} className="btn-secondary inline-flex items-center gap-2 px-3 py-2 text-sm disabled:opacity-50"><Save className="h-4 w-4" />保存代码</button>
                  <button disabled={Boolean(busy) || !sourceCode.trim()} onClick={() => applyCommand('submit-code', () => jobInterviewApi.submitCode(sessionId, session.sessionVersion, question.questionId, sourceCode), '代码已提交判题')} className="btn-primary ml-auto inline-flex items-center gap-2 px-4 py-2 text-sm disabled:opacity-50">{busy === 'submit-code' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Code2 className="h-4 w-4" />}提交代码</button>
                </div>
              </div>
            ) : (
              <div className="p-6">
                <label className="block text-sm font-medium text-stone-700 dark:text-stone-300">你的回答
                  <textarea value={answer} onChange={(event) => setAnswer(event.target.value)} maxLength={12000} className="dark-input mt-2 min-h-60 w-full p-4 leading-7" placeholder="像真实面试一样回答，可以结合项目背景、你的职责和具体实现..." />
                </label>
                <div className="mt-4 flex flex-wrap items-center gap-2">
                  <button disabled={Boolean(busy) || !answer.trim()} onClick={async () => {
                    const submitted = await applyCommand('answer', () => jobInterviewApi.submitAnswer(sessionId, session.sessionVersion, question.questionId, answer.trim()), '回答已提交');
                    if (submitted) setAnswer('');
                  }} className="btn-primary inline-flex items-center gap-2 px-4 py-2.5 text-sm disabled:opacity-50">{busy === 'answer' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}提交回答</button>
                  <div className="ml-auto flex min-w-[260px] flex-1 gap-2 sm:flex-initial">
                    <input value={clarification} onChange={(event) => setClarification(event.target.value)} maxLength={500} className="dark-input min-w-0 flex-1 px-3 py-2 text-sm" placeholder="可选：说明哪里需要澄清" />
                    <button disabled={Boolean(busy)} onClick={() => applyCommand('clarify', () => jobInterviewApi.clarify(sessionId, session.sessionVersion, clarification.trim()), '已请求面试官澄清')} className="btn-secondary inline-flex items-center gap-1.5 px-3 py-2 text-sm"><HelpCircle className="h-4 w-4" />澄清</button>
                  </div>
                </div>
              </div>
            )}
          </section>

          <aside className="space-y-5">
            <section className="surface-card p-5">
              <h2 className="text-sm font-semibold text-stone-900 dark:text-white">上一题反馈</h2>
              {assessment ? (
                <div className="mt-4 space-y-3 text-sm">
                  <div className="grid grid-cols-2 gap-3">
                    <div className="rounded-lg bg-stone-100 p-3 dark:bg-stone-800"><p className="text-xs text-stone-400">技术正确性</p><p className="mt-1 font-semibold">{scoreText(assessment.technicalCorrectness)}</p></div>
                    <div className="rounded-lg bg-stone-100 p-3 dark:bg-stone-800"><p className="text-xs text-stone-400">完整性</p><p className="mt-1 font-semibold">{scoreText(assessment.completeness)}</p></div>
                  </div>
                  {assessment.rationale && <p className="leading-6 text-stone-600 dark:text-stone-300">{assessment.rationale}</p>}
                  <dl className="grid grid-cols-2 gap-x-3 gap-y-2 text-xs text-stone-500 dark:text-stone-400">
                    <dt>资料参考</dt><dd className="text-right">{evidenceStatusText(assessment.evidenceStatus)}</dd>
                    <dt>模型耗时</dt><dd className="text-right">{assessment.latencyMs != null ? `${assessment.latencyMs} ms` : '未记录'}</dd>
                    <dt>Token</dt><dd className="text-right">{assessment.inputTokens != null || assessment.outputTokens != null ? `${assessment.inputTokens ?? 0} + ${assessment.outputTokens ?? 0}` : '未记录'}</dd>
                    <dt>重试</dt><dd className="text-right">{assessment.retryCount ?? 0} 次</dd>
                  </dl>
                  {assessment.degradedReason && <p className="rounded-lg bg-amber-50 p-3 text-xs text-amber-700 dark:bg-amber-950/30 dark:text-amber-300">评估说明：{degradedReasonText(assessment.degradedReason)}</p>}
                </div>
              ) : <p className="mt-3 text-sm leading-6 text-stone-400">提交回答或代码后展示。这里不会提前泄露标准答案。</p>}
            </section>

            <section className="surface-card p-5">
              <h2 className="text-sm font-semibold text-stone-900 dark:text-white">会话控制</h2>
              <p className="mt-2 text-xs leading-5 text-stone-400">可以提前结束并生成报告；中止的面试不会更新能力画像。</p>
              <div className="mt-4 flex flex-wrap gap-2">
                <button disabled={Boolean(busy)} onClick={() => applyCommand('finish', () => jobInterviewApi.finish(sessionId, session.sessionVersion), '正在生成面试报告')} className="btn-secondary inline-flex items-center gap-2 px-3 py-2 text-sm"><CheckCircle2 className="h-4 w-4" />结束并生成报告</button>
                <button disabled={Boolean(busy)} onClick={() => {
                  if (window.confirm('确认中止本次面试吗？中止记录不会更新能力画像。')) {
                    void applyCommand('abort', () => jobInterviewApi.abort(sessionId, session.sessionVersion, '用户主动中止'), '面试已中止');
                  }
                }} className="inline-flex items-center gap-2 rounded-xl px-3 py-2 text-sm text-red-600 hover:bg-red-50 dark:hover:bg-red-950/30"><StopCircle className="h-4 w-4" />中止</button>
              </div>
            </section>
          </aside>
        </div>
      )}

      {session.status === 'COMPLETING' && (
        <section className="surface-card p-8 text-center"><Loader2 className="mx-auto h-9 w-9 animate-spin text-primary-600" /><h2 className="mt-4 text-xl font-semibold text-stone-900 dark:text-white">正在生成面试报告</h2><p className="mt-2 text-sm text-stone-500">报告会整理每道题的回答和改进建议。</p></section>
      )}

      {terminal && (
        <section className="surface-card p-8 text-center">
          {session.status === 'COMPLETED' ? <CheckCircle2 className="mx-auto h-10 w-10 text-emerald-600" /> : <AlertTriangle className="mx-auto h-10 w-10 text-amber-600" />}
          <h2 className="mt-4 text-2xl font-semibold text-stone-900 dark:text-white">{statusLabel[session.status]}</h2>
          <p className="mx-auto mt-2 max-w-xl text-sm leading-6 text-stone-500 dark:text-stone-400">
            {session.status === 'COMPLETED'
              ? session.answeredQuestions < session.totalQuestions
                ? `报告已保存。本次有 ${session.totalQuestions - session.answeredQuestions} 题因阶段超时或提前交卷未作答，报告会按实际作答内容生成。`
                : '报告已保存，可以回看回答、反馈、模型耗时和待练习项。'
              : '本次面试未完成，不会更新能力画像。'}
          </p>
          <div className="mt-6 flex justify-center gap-3">
            {session.status === 'COMPLETED'
              ? <Link to={`/job-practice/report/${encodeURIComponent(sessionId)}`} className="btn-primary px-4 py-2.5 text-sm">查看面试报告</Link>
              : <Link to="/interviews" className="btn-primary px-4 py-2.5 text-sm">查看面试记录</Link>}
            <Link to="/job-practice" className="btn-secondary px-4 py-2.5 text-sm">返回岗位实战</Link>
          </div>
        </section>
      )}
    </div>
  );
}
