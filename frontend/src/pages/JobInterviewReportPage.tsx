import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ArrowRight,
  ChevronDown,
  Clock3,
  Database,
  FileWarning,
  Gauge,
  Loader2,
  RefreshCw,
  Sparkles,
  Target,
} from 'lucide-react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  reportApi,
  type LlmUsageItem,
  type ObjectiveFact,
  type ReportView,
} from '../api/report';
import { getErrorMessage } from '../api/request';
import { ErrorState, LoadingState } from '../components/PageState';
import PageHeader from '../components/ui/PageHeader';
import { getCapabilityDisplayName } from '../utils/displayLabels';

const stageLabel: Record<string, string> = {
  PROJECT_DEEP_DIVE: '项目经历',
  POSITION_TECH: '岗位技术',
  ALGORITHM: '算法题',
  ENGINEERING_SCENARIO: '工程场景',
};

const evidenceLabel: Record<string, string> = {
  NONE: '未使用资料',
  SUFFICIENT: '评估资料充分',
  WEAK: '评估资料有限',
  CONFLICT: '回答与参考资料有出入',
  // 兼容早期报告快照中的旧状态值。
  SUPPORTED: '有评估资料',
  CONFLICTED: '回答与参考资料有出入',
  UNAVAILABLE: '缺少评估资料',
  SOURCE_UNAVAILABLE: '评估资料不可用',
};

export function evidenceSourceLabel(evidenceId: string): string {
  const normalized = evidenceId.toLowerCase();
  if (normalized.startsWith('job:')) return '目标岗位要求';
  if (normalized.startsWith('github:')) return 'GitHub 代码';
  if (normalized.startsWith('chunk:') || normalized.startsWith('embedding:')) {
    return '个人知识库';
  }
  if (normalized.startsWith('resume:') || normalized.startsWith('candidate:')) {
    return '候选人资料';
  }
  if (normalized.startsWith('platform-')) return '平台面试资料';
  return '评估资料';
}

export function objectiveAnswerText(
  fact: Pick<ObjectiveFact, 'stage' | 'answer'>,
): string {
  if (!fact.answer) return '未作答';
  if (fact.stage === 'ALGORITHM'
      && /^\[代码提交\]\s*sha256=[0-9a-f]{64}$/i.test(fact.answer.trim())) {
    return '代码已提交；报告仅保留提交摘要，源码未在复盘中重复展示。';
  }
  return fact.answer;
}

const usageStatusLabel: Record<string, string> = {
  SUCCEEDED: '成功',
  DEGRADED: '部分完成',
  FAILED: '失败',
};

const operationLabel: Record<string, string> = {
  JOB_INTERVIEW_ANSWER_ASSESSMENT: '回答评估',
  JOB_INTERVIEW_CLARIFICATION: '题意澄清',
  INTERVIEW_REPORT_SUMMARY: '报告总结',
  BYOK_CHAT: '模型对话',
  BYOK_STREAM: '流式模型对话',
};

const judgeStatusLabel: Record<string, string> = {
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

function operationText(value: string): string {
  return operationLabel[value] || '模型调用';
}

function judgeStatusText(value: string): string {
  return judgeStatusLabel[value] || '判题状态待确认';
}

function formatMemory(memoryKb?: number | null): string {
  if (memoryKb == null) return '未记录';
  if (memoryKb < 1024) return `${memoryKb} KB`;
  return `${(memoryKb / 1024).toFixed(1)} MB`;
}

export default function JobInterviewReportPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const [report, setReport] = useState<ReportView | null>(null);
  const [usage, setUsage] = useState<LlmUsageItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [openFacts, setOpenFacts] = useState<number[]>([]);

  const load = useCallback(async (retry = false) => {
    if (!sessionId) return;
    setBusy(true);
    setError('');
    try {
      const initial = retry
        ? await reportApi.retry(sessionId)
        : await reportApi.generate(sessionId);
      setReport(initial);
      const completed = await reportApi.waitForReport(sessionId, initial);
      setReport(completed);
      if (completed.reportId) {
        try {
          const [sessionUsage, reportUsage] = await Promise.all([
            reportApi.listLlmUsage({ sessionId, limit: 100 }),
            reportApi.listLlmUsage({ reportId: completed.reportId, limit: 100 }),
          ]);
          setUsage(Array.from(
            new Map([...sessionUsage, ...reportUsage].map((item) => [item.usageId, item])).values(),
          ));
        } catch {
          setUsage([]);
        }
      }
    } catch (reason) {
      setError(getErrorMessage(reason, '报告加载失败'));
    } finally {
      setBusy(false);
      setLoading(false);
    }
  }, [sessionId]);

  useEffect(() => {
    void load();
  }, [load]);

  const usageSummary = useMemo(() => usage.reduce((summary, item) => ({
    totalTokens: summary.totalTokens + (item.totalTokens ?? 0),
    latencyMs: summary.latencyMs + item.latencyMs,
    retryCount: summary.retryCount + item.retryCount,
    cost: summary.cost + (item.estimatedCost ?? 0),
  }), { totalTokens: 0, latencyMs: 0, retryCount: 0, cost: 0 }), [usage]);

  if (loading && !report) return <LoadingState label="正在生成面试报告..." description="正在整理回答、反馈和待改进项" />;
  if (!report && error) {
    return <ErrorState title="报告暂不可用" description={error} action={(
      <div className="flex gap-2"><button onClick={() => void load()} className="btn-primary px-4 py-2 text-sm">重试</button><button onClick={() => navigate('/interviews')} className="btn-secondary px-4 py-2 text-sm">返回记录</button></div>
    )} />;
  }
  if (!report || !sessionId) return null;

  if (report.status === 'GENERATING') {
    return (
      <div className="mx-auto max-w-4xl">
        <PageHeader title="面试报告" onBack={() => navigate('/interviews')} />
        <section className="surface-card p-8 text-center">
          <Loader2 className="mx-auto h-10 w-10 animate-spin text-primary-600" />
          <h2 className="mt-4 text-xl font-semibold text-stone-900 dark:text-white">报告仍在生成</h2>
          <p className="mt-2 text-sm text-stone-500 dark:text-stone-400">可以先离开这个页面，稍后从面试记录回来查看。</p>
          {error && <p className="mt-3 text-sm text-amber-600">{error}</p>}
          <button disabled={busy} onClick={() => void load()} className="btn-secondary mt-5 inline-flex items-center gap-2 px-4 py-2 text-sm"><RefreshCw className="h-4 w-4" />刷新状态</button>
        </section>
      </div>
    );
  }

  if (report.status === 'FAILED') {
    return (
      <div className="mx-auto max-w-4xl">
        <PageHeader title="面试报告" onBack={() => navigate('/interviews')} />
        <section className="surface-card p-8 text-center">
          <FileWarning className="mx-auto h-11 w-11 text-amber-600" />
          <h2 className="mt-4 text-xl font-semibold text-stone-900 dark:text-white">报告生成失败</h2>
          <p className="mt-2 text-sm text-stone-500 dark:text-stone-400">{report.failureDetail || report.failureCode || '未返回失败详情'}</p>
          <p className="mt-2 text-xs text-stone-400">已尝试 {report.generationAttempt} 次。失败不会更新能力画像。</p>
          {report.retryable && <button disabled={busy} onClick={() => void load(true)} className="btn-primary mt-5 inline-flex items-center gap-2 px-4 py-2.5 text-sm"><RefreshCw className={`h-4 w-4 ${busy ? 'animate-spin' : ''}`} />重新生成</button>}
        </section>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader
        title="面试报告"
        description="回看每道题的回答和反馈，找到下一步最值得练习的内容。"
        onBack={() => navigate('/interviews')}
        action={<span className="rounded-md bg-emerald-50 px-2.5 py-1.5 text-xs font-medium text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300">已完成</span>}
      />

      {error && <div className="mb-5 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-300">{error}</div>}

      <div className="grid gap-5 lg:grid-cols-[1.2fr_0.8fr]">
        <section className="surface-card p-5">
          <div className="flex items-center gap-2"><Sparkles className="h-5 w-5 text-primary-600" /><h2 className="text-lg font-semibold text-stone-900 dark:text-white">面试结论</h2></div>
          <p className="mt-4 whitespace-pre-wrap text-sm leading-7 text-stone-600 dark:text-stone-300">{report.summary?.overallFeedback || '本次没有生成总结，请查看下方逐题回顾。'}</p>
          <div className="mt-5 grid gap-4 md:grid-cols-2">
            <div className="rounded-lg bg-emerald-50 p-4 dark:bg-emerald-950/25"><h3 className="text-sm font-semibold text-emerald-800 dark:text-emerald-300">做得好的地方</h3><ul className="mt-2 space-y-2 text-sm leading-6 text-emerald-900/80 dark:text-emerald-200/80">{report.summary?.strengths.length ? report.summary.strengths.map((item) => <li key={item}>• {item}</li>) : <li>本次还没有足够信息</li>}</ul></div>
            <div className="rounded-lg bg-amber-50 p-4 dark:bg-amber-950/25"><h3 className="text-sm font-semibold text-amber-800 dark:text-amber-300">优先改进</h3><ul className="mt-2 space-y-2 text-sm leading-6 text-amber-900/80 dark:text-amber-200/80">{report.summary?.improvements.length ? report.summary.improvements.map((item) => <li key={item}>• {item}</li>) : <li>本次还没有明确结论</li>}</ul></div>
          </div>
        </section>

        <section className="surface-card p-5">
          <div className="flex items-center gap-2"><Gauge className="h-5 w-5 text-primary-600" /><h2 className="text-lg font-semibold text-stone-900 dark:text-white">我的模型用量</h2></div>
          {usage.length === 0 ? <p className="mt-4 text-sm leading-6 text-stone-400">本次没有可展示的用量记录。未记录不代表 Token 为 0。</p> : (
            <>
              <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
                <div className="rounded-xl bg-stone-100 p-3 dark:bg-stone-800"><p className="text-xs text-stone-400">总 Token</p><p className="mt-1 text-lg font-semibold">{usageSummary.totalTokens.toLocaleString()}</p></div>
                <div className="rounded-xl bg-stone-100 p-3 dark:bg-stone-800"><p className="text-xs text-stone-400">累计耗时</p><p className="mt-1 text-lg font-semibold">{(usageSummary.latencyMs / 1000).toFixed(1)}s</p></div>
                <div className="rounded-xl bg-stone-100 p-3 dark:bg-stone-800"><p className="text-xs text-stone-400">重试次数</p><p className="mt-1 text-lg font-semibold">{usageSummary.retryCount}</p></div>
                <div className="rounded-xl bg-stone-100 p-3 dark:bg-stone-800"><p className="text-xs text-stone-400">预估费用</p><p className="mt-1 text-lg font-semibold">{usageSummary.cost > 0 ? usageSummary.cost.toFixed(4) : '未提供'}</p></div>
              </div>
              <div className="mt-4 space-y-2">
                {usage.slice(0, 5).map((item) => <div key={item.usageId} className="flex items-center justify-between gap-3 text-xs text-stone-500 dark:text-stone-400"><span className="truncate">{operationText(item.operation)} · {item.provider || '未知服务'} / {item.model || '未知模型'}</span><span className={item.status === 'SUCCEEDED' ? 'text-emerald-600' : 'text-amber-600'}>{usageStatusLabel[item.status] || '状态待确认'}</span></div>)}
              </div>
            </>
          )}
        </section>
      </div>

      <section className="surface-card mt-5 overflow-hidden">
        <div className="border-b border-stone-200 p-5 dark:border-stone-800"><h2 className="text-lg font-semibold text-stone-900 dark:text-white">逐题回顾</h2><p className="mt-1 text-sm text-stone-500 dark:text-stone-400">展开查看你的回答、反馈、判题和评估参考资料。</p></div>
        <div className="divide-y divide-stone-100 dark:divide-stone-800">
          {report.objectiveFacts.map((fact, factIndex) => {
            const open = openFacts.includes(fact.questionId);
            return (
              <div key={fact.questionId}>
                <button onClick={() => setOpenFacts((current) => open ? current.filter((id) => id !== fact.questionId) : [...current, fact.questionId])} className="flex w-full items-center gap-4 px-6 py-4 text-left hover:bg-stone-50 dark:hover:bg-stone-900/50">
                  <span className="shrink-0 text-xs text-stone-400">第 {factIndex + 1} 项</span><div className="min-w-0 flex-1"><p className="truncate text-sm font-medium text-stone-900 dark:text-white">{fact.question}</p><p className="mt-1 text-xs text-stone-400">{stageLabel[fact.stage] || '其他环节'} · {evidenceLabel[fact.evidenceStatus || 'NONE'] || '资料状态未知'}{fact.judgeStatus ? ` · ${judgeStatusText(fact.judgeStatus)}` : ''}</p></div><ChevronDown className={`h-4 w-4 text-stone-400 transition-transform ${open ? 'rotate-180' : ''}`} />
                </button>
                {open && <div className="border-t border-stone-100 bg-stone-50 px-6 py-5 text-sm dark:border-stone-800 dark:bg-stone-950/30"><h3 className="font-medium text-stone-700 dark:text-stone-200">{fact.stage === 'ALGORITHM' ? '代码提交' : '你的回答'}</h3><p className="mt-2 whitespace-pre-wrap leading-7 text-stone-600 dark:text-stone-300">{objectiveAnswerText(fact)}</p>{fact.feedback && <><h3 className="mt-5 font-medium text-stone-700 dark:text-stone-200">{fact.stage === 'ALGORITHM' ? '判题诊断' : '反馈'}</h3><p className="mt-2 whitespace-pre-wrap leading-7 text-stone-600 dark:text-stone-300">{fact.feedback}</p></>}{fact.stage === 'ALGORITHM' ? <div className="mt-5 grid gap-3 sm:grid-cols-4"><div className="rounded-lg bg-white p-3 dark:bg-stone-900"><p className="text-xs text-stone-400">判题结果</p><p className={`mt-1 font-medium ${fact.judgeStatus === 'ACCEPTED' ? 'text-emerald-600' : 'text-amber-600'}`}>{fact.judgeStatus ? judgeStatusText(fact.judgeStatus) : '未判题'}</p></div><div className="rounded-lg bg-white p-3 dark:bg-stone-900"><p className="text-xs text-stone-400">隐藏用例</p><p className="mt-1 font-medium">{fact.passedCount != null && fact.totalCount != null ? `${fact.passedCount}/${fact.totalCount}` : '未记录'}</p></div><div className="rounded-lg bg-white p-3 dark:bg-stone-900"><p className="text-xs text-stone-400">运行耗时</p><p className="mt-1 font-medium">{fact.executionTimeMs != null ? `${fact.executionTimeMs} ms` : '未记录'}</p></div><div className="rounded-lg bg-white p-3 dark:bg-stone-900"><p className="text-xs text-stone-400">语言 / 内存</p><p className="mt-1 font-medium">{fact.codingLanguage || '未记录'} · {formatMemory(fact.memoryKb)}</p></div></div> : <div className="mt-5 grid gap-3 sm:grid-cols-4"><div className="rounded-lg bg-white p-3 dark:bg-stone-900"><p className="text-xs text-stone-400">正确性</p><p className="mt-1 font-medium">{fact.technicalCorrectness ?? '未评估'}</p></div><div className="rounded-lg bg-white p-3 dark:bg-stone-900"><p className="text-xs text-stone-400">完整性</p><p className="mt-1 font-medium">{fact.completeness ?? '未评估'}</p></div><div className="rounded-lg bg-white p-3 dark:bg-stone-900"><p className="text-xs text-stone-400">资料引用</p><p className="mt-1 font-medium">{fact.evidenceIds.length}</p></div><div className="rounded-lg bg-white p-3 dark:bg-stone-900"><p className="text-xs text-stone-400">来源状态</p><p className={`mt-1 font-medium ${fact.sourceAvailable ? 'text-emerald-600' : 'text-amber-600'}`}>{fact.sourceAvailable ? '来源仍有效' : '来源已删除或不可用'}</p></div></div>}{fact.evidenceIds.length > 0 && <div className="mt-4"><h3 className="font-medium text-stone-700 dark:text-stone-200">本题评估参考资料</h3><ul className="mt-2 space-y-2">{fact.evidenceIds.map((evidenceId) => <li key={evidenceId} className="rounded-lg border border-stone-200 bg-white px-3 py-2 dark:border-stone-800 dark:bg-stone-900"><p className="text-xs font-medium text-stone-700 dark:text-stone-200">{evidenceSourceLabel(evidenceId)}</p><p className="mt-1 break-all font-mono text-[11px] text-stone-400">{evidenceId}</p></li>)}</ul></div>}</div>}
              </div>
            );
          })}
        </div>
      </section>

      <section className="mt-5">
        <div className="mb-4 flex items-end justify-between"><div><h2 className="text-lg font-semibold text-stone-900 dark:text-white">接下来练什么</h2><p className="mt-1 text-sm text-stone-500 dark:text-stone-400">优先列出本次面试中最值得改进的三项内容。</p></div><Link to="/training" className="text-sm font-medium text-primary-700 dark:text-primary-300">进入专项训练</Link></div>
        {report.gaps.length === 0 ? <div className="surface-card p-5 text-sm text-stone-500">本次没有形成明确的待改进项，可以展开逐题回顾继续检查。</div> : <div className="grid gap-4 md:grid-cols-3">{report.gaps.map((gap) => <div key={gap.capabilityAtomId} className="surface-card p-5"><Target className="h-5 w-5 text-amber-600" /><h3 className="mt-4 font-semibold text-stone-900 dark:text-white">{getCapabilityDisplayName(gap.capabilityAtomId, gap.capabilityName)}</h3><p className="mt-2 text-sm leading-6 text-stone-500 dark:text-stone-400">{gap.reason}</p><p className="mt-4 flex items-center gap-1.5 text-xs text-stone-400"><Database className="h-3.5 w-3.5" />来自 {gap.evidenceRecordIds.length} 道题的反馈</p>{gap.trainingTaskId && <Link to={`/training?task=${encodeURIComponent(gap.trainingTaskId)}`} className="mt-4 inline-flex items-center gap-1.5 text-sm font-medium text-primary-700 dark:text-primary-300">开始推荐训练<ArrowRight className="h-4 w-4" /></Link>}</div>)}</div>}
      </section>

      <footer className="mt-6 flex items-center gap-2 text-xs text-stone-400"><Clock3 className="h-3.5 w-3.5" />报告完成于 {report.completedAt ? new Date(report.completedAt).toLocaleString() : '未记录'}</footer>
    </div>
  );
}
