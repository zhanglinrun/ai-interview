import { useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import PageHeader from '../components/ui/PageHeader';
import { EmptyState, LoadingState } from '../components/PageState';
import { getErrorMessage } from '../api/request';
import { ragTraceApi, type RagTraceDetail, type RagTraceRun } from '../api/ragTrace';
import { unifiedTraceApi, type UnifiedTrace } from '../api/unifiedTrace';

function short(value: string | null | undefined, length = 220) {
  if (!value) return '—';
  return value.length > length ? `${value.slice(0, length)}…` : value;
}

export default function RagTracePage() {
  const [runs, setRuns] = useState<RagTraceRun[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [detail, setDetail] = useState<RagTraceDetail | null>(null);
  const [unified, setUnified] = useState<UnifiedTrace | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [error, setError] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const next = await ragTraceApi.list(50);
      setRuns(next);
      setSelectedId(prev => prev && next.some(item => item.traceId === prev) ? prev : (next[0]?.traceId ?? ''));
    } catch (err) {
      setError(getErrorMessage(err, '加载 RAG Trace 失败'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      setUnified(null);
      return;
    }
    setLoadingDetail(true);
    Promise.all([
      ragTraceApi.get(selectedId),
      unifiedTraceApi.get(selectedId).catch(() => null),
    ])
      .then(([nextDetail, nextUnified]) => {
        setDetail(nextDetail);
        setUnified(nextUnified);
      })
      .catch(err => setError(getErrorMessage(err, '加载 Trace 详情失败')))
      .finally(() => setLoadingDetail(false));
  }, [selectedId]);

  return (
    <div className="mx-auto max-w-6xl space-y-5">
      <PageHeader
        eyebrow="RAG Observability"
        title="RAG 阶段 Trace"
        description="按权限隔离回放意图、改写、数据源路由、候选检索、重排、引用和最终回答，便于定位降级与回归。"
      />
      <div className="flex flex-wrap items-end gap-3">
        <label className="min-w-[300px] flex-1 space-y-1 text-sm text-stone-600 dark:text-stone-300">
          <span>选择 Trace</span>
          <select className="dark-input w-full px-3 py-2 text-sm" value={selectedId} onChange={e => setSelectedId(e.target.value)}>
            {runs.length === 0 && <option value="">暂无 Trace</option>}
            {runs.map(run => <option key={run.traceId} value={run.traceId}>{run.traceId.slice(0, 12)} · {short(run.question, 70)}</option>)}
          </select>
        </label>
        <button type="button" onClick={() => void load()} className="btn-secondary inline-flex items-center gap-2 px-4 py-2 text-sm">
          <RefreshCw className="h-4 w-4" />刷新
        </button>
      </div>
      {error && <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200">{error}</div>}
      {loading ? <LoadingState className="flex min-h-[30vh] items-center justify-center" /> : !detail ? (
        <EmptyState title="暂无 RAG Trace" description="先在知识库问答发送一个问题，回答完成后 Trace 会出现在这里。" />
      ) : loadingDetail ? <LoadingState className="flex min-h-[30vh] items-center justify-center" /> : (
        <div className="space-y-4">
          <section className="surface-card grid gap-3 p-4 sm:grid-cols-4">
            <div><p className="text-xs text-stone-500">问题</p><p className="mt-1 text-sm text-stone-900 dark:text-white">{detail.run.question}</p></div>
            <div><p className="text-xs text-stone-500">路由</p><p className="mt-1 text-sm text-stone-900 dark:text-white">{detail.run.routeSource ?? '—'} · {detail.run.routeIntent ?? '—'}</p></div>
            <div><p className="text-xs text-stone-500">耗时</p><p className="mt-1 text-sm text-stone-900 dark:text-white">{detail.run.latencyMs ?? 0} ms</p></div>
            <div><p className="text-xs text-stone-500">状态</p><p className="mt-1 text-sm text-emerald-600">{detail.run.status}</p></div>
          </section>
          {unified && <section className="surface-card space-y-3 p-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 className="text-sm font-semibold text-stone-900 dark:text-white">统一 Trace 关联</h2>
              <span className="text-xs text-stone-500">Agent {unified.agentRuns.length} · RAG {unified.ragRuns.length} · Tool {unified.toolRuns.length} · LLM {unified.llmUsage.length}</span>
            </div>
            <div className="grid gap-2 md:grid-cols-2">
              {unified.agentRuns.map(run => <div key={run.agentRunId} className="rounded-lg border border-stone-100 p-3 text-xs dark:border-stone-800"><div className="flex justify-between"><span>Agent · {run.operation ?? 'interview'}</span><span>{run.status} · {run.latencyMs ?? 0} ms</span></div><p className="mt-1 text-stone-500">runId: {run.agentRunId}{run.degradedReason ? ` · ${run.degradedReason}` : ''}</p></div>)}
              {unified.ragRuns.map(run => <div key={run.ragRunId} className="rounded-lg border border-stone-100 p-3 text-xs dark:border-stone-800"><div className="flex justify-between"><span>RAG · {run.ragRunId}</span><span>{run.status} · {run.latencyMs ?? 0} ms</span></div>{run.degradedReason && <p className="mt-1 text-amber-600">降级：{run.degradedReason}</p>}</div>)}
              {unified.toolRuns.map(tool => <div key={tool.toolRunId} className="rounded-lg border border-stone-100 p-3 text-xs dark:border-stone-800"><div className="flex justify-between"><span>Tool · {tool.toolName}</span><span>{tool.status} · {tool.latencyMs ?? 0} ms</span></div><p className="mt-1 text-stone-500">{tool.ragRunId ? `ragRunId: ${tool.ragRunId}` : '未关联 RAG Run'}{tool.cacheHit ? ' · cache hit' : ''}</p></div>)}
            </div>
          </section>}
          <section className="surface-card space-y-3 p-4">
            <h2 className="text-sm font-semibold text-stone-900 dark:text-white">阶段时间线</h2>
            <ol className="space-y-2">
              {detail.stages.map((stage, index) => <li key={stage.id ?? index} className="rounded-lg border border-stone-100 p-3 dark:border-stone-800">
                <div className="flex flex-wrap items-center gap-2"><span className="rounded bg-primary-50 px-2 py-0.5 text-xs font-medium text-primary-700 dark:bg-primary-950/40 dark:text-primary-200">{stage.stage}</span><span className="text-xs text-stone-400">{stage.status} · {stage.latencyMs ?? 0} ms</span>{stage.dataSource && <span className="text-xs text-indigo-500">{stage.dataSource}</span>} {stage.provider && <span className="text-xs text-stone-500">{stage.provider}/{stage.modelName ?? 'default'}</span>}</div>
                <p className="mt-1 text-xs text-stone-500">输入：{short(stage.inputSummary)}</p><p className="mt-1 text-xs text-stone-600 dark:text-stone-300">输出：{short(stage.outputSummary)}</p>
                {(stage.filterJson || stage.fallbackReason) && <p className="mt-1 text-xs text-amber-600 dark:text-amber-300">{stage.fallbackReason ? `降级：${stage.fallbackReason}` : `过滤：${short(stage.filterJson, 160)}`}</p>}
              </li>)}
            </ol>
          </section>
          <section className="surface-card space-y-3 p-4">
            <h2 className="text-sm font-semibold text-stone-900 dark:text-white">候选与引用</h2>
            <p className="text-xs text-stone-500">候选 {detail.candidates.length} 条 · 引用 {detail.citations.length} 条</p>
            <div className="grid gap-2 md:grid-cols-2">{detail.candidates.slice(0, 12).map(item => <div key={item.id} className="rounded-lg border border-stone-100 p-3 text-xs dark:border-stone-800"><div className="flex justify-between gap-2"><span>{item.stage} · #{item.rankNo ?? '—'}</span><span>原始 {item.score?.toFixed(3) ?? '—'} / 重排 {item.rerankScore?.toFixed(3) ?? '—'}</span></div><p className="mt-1 text-stone-500">{short(item.snippet, 180)}</p></div>)}</div>
            <div className="flex flex-wrap gap-2">{detail.citations.map(item => <span key={item.id} className={`rounded-full px-2 py-1 text-xs ${item.valid && item.cited ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/30 dark:text-emerald-300' : 'bg-amber-50 text-amber-700 dark:bg-amber-950/30 dark:text-amber-300'}`}>[{item.citationIndex}] {item.valid ? '有效' : '无效'} · {item.sourceLocator ?? '—'}</span>)}</div>
          </section>
          {detail.answer && <section className="surface-card space-y-2 p-4"><h2 className="text-sm font-semibold text-stone-900 dark:text-white">回答快照</h2><div className="flex gap-3 text-xs text-stone-500"><span>grounded: {detail.answer.groundedStatus ?? '—'}</span><span>置信度: {detail.answer.confidence == null ? '—' : `${(detail.answer.confidence * 100).toFixed(0)}%`}</span><span>tokens: {detail.answer.tokenCount ?? 0}</span></div><p className="whitespace-pre-wrap text-sm text-stone-700 dark:text-stone-200">{detail.answer.answer}</p></section>}
        </div>
      )}
    </div>
  );
}
