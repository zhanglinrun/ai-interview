import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import { interviewApi } from '../api/interview';
import { getErrorMessage } from '../api/request';
import type { AgentTraceCatalogItem, AgentTracePlayback, AgentTraceSpan } from '../types/interview';
import { EmptyState, LoadingState } from '../components/PageState';
import PageHeader from '../components/ui/PageHeader';
import { formatDate } from '../utils/date';
import type { FlowTone } from './ragTraceFlow';
import {
  catalogHasProcess,
  catalogOptionLabel,
  defaultSpanId,
  diagnoseTrace,
  flattenSpans,
  labelSessionStatus,
  resolveSpans,
  spanTone,
} from './agentTraceFlow';

function toneClass(tone: FlowTone): string {
  if (tone === 'fail') return 'border-rose-200 bg-rose-50 text-rose-800 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-200';
  if (tone === 'warn') return 'border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200';
  if (tone === 'skip') return 'border-stone-200 bg-stone-50 text-stone-500 dark:border-stone-800 dark:bg-stone-900 dark:text-stone-400';
  return 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950/30 dark:text-emerald-200';
}

function kindBadge(kind: string): string {
  if (kind === 'chat') return 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-200';
  if (kind === 'tool') return 'bg-amber-100 text-amber-700 dark:bg-amber-950/40 dark:text-amber-200';
  return 'bg-stone-100 text-stone-600 dark:bg-stone-800 dark:text-stone-300';
}

function SpanDetail({ span }: { span: AgentTraceSpan }) {
  const facts = [
    { label: '类型', value: span.kind },
    { label: '角色', value: span.role || '—' },
    { label: '动作', value: span.action || '—' },
    { label: '状态', value: span.status || '—' },
    { label: '耗时', value: span.latencyMs != null ? `${span.latencyMs} ms` : '未记录' },
    { label: '模型', value: span.model || '未记录' },
    { label: 'Token', value: span.inputTokens != null || span.outputTokens != null
      ? `in ${span.inputTokens ?? '—'} / out ${span.outputTokens ?? '—'}`
      : '未记录' },
  ];
  return (
    <section className="surface-card space-y-4 p-4">
      <div>
        <p className="text-xs font-medium text-primary-700 dark:text-primary-300">{span.title}</p>
        <p className="mt-1 text-sm text-stone-500">
          {span.kind === 'chat' ? '一次真实模型调用。' : span.kind === 'tool' ? '一次工具执行，挂在触发它的 Chat 下面。' : '编排状态或决策，不是模型调用。'}
        </p>
      </div>
      <dl className="grid gap-3 sm:grid-cols-2">
        {facts.map(fact => (
          <div key={fact.label} className="rounded-lg border border-stone-100 px-3 py-2 dark:border-stone-800">
            <dt className="text-xs text-stone-400">{fact.label}</dt>
            <dd className="mt-1 text-sm text-stone-800 dark:text-stone-100">{fact.value}</dd>
          </div>
        ))}
      </dl>
      <div className="grid gap-3 lg:grid-cols-2">
        <div className="rounded-lg border border-stone-100 p-3 dark:border-stone-800">
          <p className="text-xs font-medium text-stone-500">Input</p>
          <pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap text-sm leading-6 text-stone-700 dark:text-stone-200">
            {span.input || '（空）'}
          </pre>
        </div>
        <div className="rounded-lg border border-stone-100 p-3 dark:border-stone-800">
          <p className="text-xs font-medium text-stone-500">Output</p>
          <pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap text-sm leading-6 text-stone-700 dark:text-stone-200">
            {span.output || '（空）'}
          </pre>
        </div>
      </div>
    </section>
  );
}

export default function AgentTracePage() {
  const [catalog, setCatalog] = useState<AgentTraceCatalogItem[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [onlyWithSteps, setOnlyWithSteps] = useState(true);
  const [playback, setPlayback] = useState<AgentTracePlayback | null>(null);
  const [loadingList, setLoadingList] = useState(true);
  const [loadingTrace, setLoadingTrace] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [spanId, setSpanId] = useState('');

  const visibleCatalog = useMemo(
    () => onlyWithSteps ? catalog.filter(catalogHasProcess) : catalog,
    [catalog, onlyWithSteps],
  );

  useEffect(() => {
    if (selectedId && visibleCatalog.some(item => item.sessionId === selectedId)) {
      return;
    }
    setSelectedId(visibleCatalog[0]?.sessionId ?? '');
  }, [selectedId, visibleCatalog]);

  const loadCatalog = async () => {
    setLoadingList(true);
    setError(null);
    try {
      const next = await interviewApi.listAgentTraces();
      setCatalog(next);
      setSelectedId(prev => {
        if (prev && next.some(item => item.sessionId === prev)) {
          return prev;
        }
        return (next.find(catalogHasProcess) ?? next[0])?.sessionId ?? '';
      });
    } catch (err) {
      setError(getErrorMessage(err, '加载 Agent Trace 失败'));
    } finally {
      setLoadingList(false);
    }
  };

  useEffect(() => {
    void loadCatalog();
  }, []);

  useEffect(() => {
    if (!selectedId) {
      setPlayback(null);
      return;
    }
    setLoadingTrace(true);
    setError(null);
    interviewApi.getAgentTracePlayback(selectedId)
      .then(next => {
        setPlayback(next);
        setSpanId(defaultSpanId(flattenSpans(resolveSpans(next))));
      })
      .catch(err => {
        setPlayback(null);
        setError(getErrorMessage(err, '加载 Agent Trace 失败'));
      })
      .finally(() => setLoadingTrace(false));
  }, [selectedId]);

  const views = useMemo(() => flattenSpans(resolveSpans(playback)), [playback]);
  const diagnosis = playback ? diagnoseTrace(playback, views) : null;
  const selectedSpan = views.find(item => item.span.spanId === spanId)?.span ?? views[0]?.span;
  const selected = catalog.find(item => item.sessionId === selectedId);

  return (
    <div className="mx-auto max-w-6xl space-y-5">
      <PageHeader
        eyebrow="Agent Trace"
        title="出题过程回放"
        description="按阶段看：定大纲、第 N 题。Chat 是一次真实模型调用，Tool 挂在触发它的 Chat 下面。点开看截断后的 Input / Output。"
        action={(
          <Link to="/interview" className="btn-primary px-4 py-2 text-sm">
            开一场模拟面试
          </Link>
        )}
      />

      <div className="rounded-lg border border-primary-100 bg-primary-50/60 px-4 py-3 text-sm text-primary-900 dark:border-primary-900 dark:bg-primary-950/30 dark:text-primary-200">
        请用文字模拟面试验收。左边应按「定大纲 → 第 1 题」分开，而不是把 Interviewer / Critic 挂在 plan 下面。
        {' '}
        <Link to="/interview" className="underline underline-offset-2">去开一场</Link>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <label className="min-w-[280px] flex-1 space-y-1 text-sm text-stone-600 dark:text-stone-300">
          <span>选择一场面试</span>
          <select
            className="dark-input w-full px-3 py-2 text-sm"
            value={selectedId}
            onChange={e => setSelectedId(e.target.value)}
          >
            {visibleCatalog.length === 0 && <option value="">还没有可回放的面试</option>}
            {visibleCatalog.map(item => (
              <option key={item.sessionId} value={item.sessionId}>
                {catalogOptionLabel(item, item.createdAt ? formatDate(item.createdAt) : '')}
              </option>
            ))}
          </select>
        </label>
        <label className="flex items-center gap-2 pb-2 text-sm text-stone-600 dark:text-stone-300">
          <input
            type="checkbox"
            checked={onlyWithSteps}
            onChange={e => setOnlyWithSteps(e.target.checked)}
          />
          只看有过程的
        </label>
        <button type="button" onClick={() => void loadCatalog()} className="inline-flex items-center gap-2 rounded-lg btn-secondary px-4 py-2 text-sm">
          <RefreshCw className="h-4 w-4" />
          刷新
        </button>
      </div>

      {error && (
        <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200">
          {error}
        </div>
      )}

      {loadingList || loadingTrace ? (
        <LoadingState className="flex min-h-[30vh] items-center justify-center" />
      ) : !playback || !selectedSpan || !diagnosis ? (
        <EmptyState
          title="还没有可回放的 span"
          description="开一场文字模拟面试。创建时会留下 Planner 的 Chat，答完第一题会留下 Interviewer / Critic。"
          action={(
            <Link to="/interview" className="btn-primary mt-4 px-4 py-2 text-sm">开一场模拟面试</Link>
          )}
        />
      ) : (
        <div className="space-y-4">
          <section className={`rounded-xl border px-4 py-3 ${toneClass(diagnosis.tone)}`}>
            <p className="text-sm font-semibold">{diagnosis.title}</p>
            <p className="mt-1 text-sm leading-6">{diagnosis.detail}</p>
            {playback.acts.length > 0 && (
              <div className="mt-3 flex flex-wrap gap-2">
                {playback.acts.filter(act => act.questionIndex != null).map(act => (
                  <span
                    key={`act-${act.questionIndex}`}
                    className="rounded-full border border-current/20 px-2 py-0.5 text-xs"
                  >
                    第 {(act.questionIndex ?? 0) + 1} 题
                    {act.criticApproved === false ? ' · 未过审' : act.criticApproved ? ' · 已过审' : ''}
                    {` · Reflexion ${act.reflexionRounds}`}
                  </span>
                ))}
                <span className="rounded-full border border-current/20 px-2 py-0.5 text-xs">
                  全场 Reflexion {playback.reflexionRounds} 轮
                </span>
              </div>
            )}
            {selected && (
              <p className="mt-2 text-xs opacity-80">
                {selected.orphanRun ? '编排运行' : '模拟面试'}
                {' · '}
                {labelSessionStatus(selected.status)}
                {' · '}
                {selected.stepCount} 步
                {selected.sessionId ? ` · ${selected.sessionId.slice(0, 8)}…` : ''}
              </p>
            )}
          </section>

          <div className="grid gap-4 lg:grid-cols-[minmax(16rem,20rem)_1fr]">
            <section className="surface-card p-3">
              <h2 className="mb-2 px-1 text-sm font-semibold text-stone-900 dark:text-white">Span 树</h2>
              <ol className="space-y-1">
                {views.map(({ span, depth }) => (
                  <li key={span.spanId} style={{ paddingLeft: depth * 16 }}>
                    <button
                      type="button"
                      onClick={() => setSpanId(span.spanId)}
                      className={`flex w-full items-center gap-2 rounded-lg border px-2 py-1.5 text-left text-sm ${
                        selectedSpan.spanId === span.spanId
                          ? 'border-primary-400 bg-primary-50 dark:bg-primary-950/40'
                          : 'border-transparent hover:bg-stone-50 dark:hover:bg-stone-800'
                      }`}
                    >
                      <span className={`rounded px-1.5 py-0.5 text-[10px] uppercase ${kindBadge(span.kind)}`}>
                        {span.kind}
                      </span>
                      <span className={`min-w-0 flex-1 truncate ${spanTone(span) === 'fail' ? 'text-rose-700 dark:text-rose-200' : 'text-stone-800 dark:text-stone-100'}`}>
                        {span.title}
                      </span>
                      {span.latencyMs != null && (
                        <span className="shrink-0 text-[11px] text-stone-400">{span.latencyMs}ms</span>
                      )}
                    </button>
                  </li>
                ))}
              </ol>
            </section>
            <SpanDetail span={selectedSpan} />
          </div>
        </div>
      )}
    </div>
  );
}

export { resolveOrchestrationState } from '../utils/agentTrace';
