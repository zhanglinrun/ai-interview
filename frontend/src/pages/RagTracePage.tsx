import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ChevronDown, ChevronRight, RefreshCw } from 'lucide-react';
import PageHeader from '../components/ui/PageHeader';
import { EmptyState, LoadingState } from '../components/PageState';
import { getErrorMessage } from '../api/request';
import { ragTraceApi, type RagTraceDetail, type RagTraceRun } from '../api/ragTrace';
import { formatDate } from '../utils/date';
import {
  allObservations,
  buildRagTraceTree,
  formatLatency,
  labelRunStatus,
  observationTypeLabel,
  waterfallShare,
  type ObservationType,
  type RagTraceTree,
  type TraceDocument,
  type TraceField,
  type TraceObservation,
} from './ragTraceFlow';

type DetailTab = 'fields' | 'input' | 'output' | 'documents';

function short(value: string | null | undefined, length = 56) {
  if (!value) return '（无问题）';
  return value.length > length ? `${value.slice(0, length)}…` : value;
}

function typeBadge(type: ObservationType): string {
  if (type === 'retriever') return 'bg-sky-100 text-sky-800 dark:bg-sky-950/50 dark:text-sky-200';
  if (type === 'generation') return 'bg-violet-100 text-violet-800 dark:bg-violet-950/50 dark:text-violet-200';
  if (type === 'chain') return 'bg-stone-800 text-white dark:bg-stone-200 dark:text-stone-900';
  return 'bg-stone-100 text-stone-600 dark:bg-stone-800 dark:text-stone-300';
}

function statusDot(status: TraceObservation['status']): string {
  if (status === 'fail') return 'bg-rose-500';
  if (status === 'warn') return 'bg-amber-500';
  return 'bg-emerald-500';
}

function defaultTab(observation: TraceObservation): DetailTab {
  if (observation.fields.length > 0) return 'fields';
  if (observation.type === 'retriever' && observation.documents.length > 0) return 'documents';
  return 'output';
}

export default function RagTracePage() {
  const [runs, setRuns] = useState<RagTraceRun[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [detail, setDetail] = useState<RagTraceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [error, setError] = useState('');
  const [activeId, setActiveId] = useState('root');
  const [tab, setTab] = useState<DetailTab>('fields');
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const next = await ragTraceApi.list(50);
      setRuns(next);
      setSelectedId(prev => prev && next.some(item => item.traceId === prev) ? prev : (next[0]?.traceId ?? ''));
    } catch (err) {
      setError(getErrorMessage(err, '加载提问记录失败'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return;
    }
    setLoadingDetail(true);
    ragTraceApi.get(selectedId)
      .then(next => {
        setDetail(next);
        setActiveId('root');
        setCollapsed({});
        setTab('fields');
      })
      .catch(err => setError(getErrorMessage(err, '加载提问记录失败')))
      .finally(() => setLoadingDetail(false));
  }, [selectedId]);

  const tree = useMemo(() => detail ? buildRagTraceTree(detail) : null, [detail]);
  const current = tree
    ? allObservations(tree).find(item => item.id === activeId) ?? tree.root
    : null;

  useEffect(() => {
    if (!current) return;
    setTab(defaultTab(current));
  }, [current?.id]);

  return (
    <div className="mx-auto max-w-[88rem] space-y-4">
      <PageHeader
        eyebrow="知识库问答"
        title="问答过程回放"
        description="一次提问是一条 Trace。中间是执行树，右边是当前步骤的字段、输入和输出。检索下的多路是同一次问答里的多次召回，不是缺步骤。"
      />

      {error && (
        <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200">
          {error}
        </div>
      )}

      {loading ? (
        <LoadingState className="flex min-h-[40vh] items-center justify-center" />
      ) : runs.length === 0 ? (
        <EmptyState title="还没有提问记录" description="先在知识库问答问完一题。回答保存后，这里会出现整次问答的执行树。" />
      ) : (
        <div className="grid min-h-[38rem] gap-3 lg:grid-cols-[220px_minmax(360px,440px)_minmax(0,1fr)]">
          <TraceList
            runs={runs}
            selectedId={selectedId}
            onSelect={setSelectedId}
            onRefresh={() => void load()}
          />
          {loadingDetail || !tree || !current ? (
            <div className="surface-card flex min-h-[20rem] items-center justify-center lg:col-span-2">
              <LoadingState />
            </div>
          ) : (
            <>
              <RunTree
                tree={tree}
                activeId={current.id}
                collapsed={collapsed}
                onSelect={setActiveId}
                onToggle={id => setCollapsed(prev => ({ ...prev, [id]: !prev[id] }))}
              />
              <ObservationPane observation={current} tab={tab} onTab={setTab} />
            </>
          )}
        </div>
      )}
    </div>
  );
}

function TraceList({
  runs,
  selectedId,
  onSelect,
  onRefresh,
}: {
  runs: RagTraceRun[];
  selectedId: string;
  onSelect: (id: string) => void;
  onRefresh: () => void;
}) {
  return (
    <section className="surface-card flex flex-col overflow-hidden">
      <div className="flex items-center justify-between border-b border-stone-100 px-3 py-2 dark:border-stone-800">
        <h2 className="text-xs font-semibold tracking-wide text-stone-500">Traces</h2>
        <button type="button" onClick={onRefresh} className="text-stone-400 hover:text-stone-700 dark:hover:text-stone-200" aria-label="刷新">
          <RefreshCw className="h-3.5 w-3.5" />
        </button>
      </div>
      <ul className="max-h-[36rem] flex-1 overflow-y-auto">
        {runs.map(run => {
          const selected = run.traceId === selectedId;
          return (
            <li key={run.traceId}>
              <button
                type="button"
                onClick={() => onSelect(run.traceId)}
                className={`w-full border-b border-stone-100 px-3 py-2.5 text-left dark:border-stone-800 ${
                  selected ? 'bg-primary-50 dark:bg-primary-950/30' : 'hover:bg-stone-50 dark:hover:bg-stone-800/60'
                }`}
              >
                <p className="line-clamp-2 text-sm text-stone-900 dark:text-stone-100">{short(run.question, 72)}</p>
                <p className="mt-1 text-[11px] text-stone-400">
                  {formatLatency(run.latencyMs)}
                  {' · '}
                  {labelRunStatus(run.status)}
                  {' · '}
                  {formatDate(run.createdAt)}
                </p>
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function RunTree({
  tree,
  activeId,
  collapsed,
  onSelect,
  onToggle,
}: {
  tree: RagTraceTree;
  activeId: string;
  collapsed: Record<string, boolean>;
  onSelect: (id: string) => void;
  onToggle: (id: string) => void;
}) {
  return (
    <section className="surface-card flex flex-col overflow-hidden">
      <div className="border-b border-stone-100 px-3 py-2 dark:border-stone-800">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-xs font-semibold tracking-wide text-stone-500">Run tree</h2>
          <Link to="/knowledgebase/chat" className="text-[11px] text-primary-700 underline underline-offset-2 dark:text-primary-300">
            去问一题
          </Link>
        </div>
        <p className="mt-1 text-[11px] leading-4 text-stone-400">{tree.note}</p>
      </div>
      <ol className="flex-1 space-y-0.5 overflow-y-auto p-2">
        <TreeRow
          observation={tree.root}
          activeId={activeId}
          depth={0}
          totalMs={tree.totalMs}
          collapsed={collapsed}
          onSelect={onSelect}
          onToggle={onToggle}
        />
        {tree.children.map(child => (
          <TreeRow
            key={child.id}
            observation={child}
            activeId={activeId}
            depth={1}
            totalMs={tree.totalMs}
            collapsed={collapsed}
            onSelect={onSelect}
            onToggle={onToggle}
          />
        ))}
      </ol>
    </section>
  );
}

function TreeRow({
  observation,
  activeId,
  depth,
  totalMs,
  collapsed,
  onSelect,
  onToggle,
}: {
  observation: TraceObservation;
  activeId: string;
  depth: number;
  totalMs: number;
  collapsed: Record<string, boolean>;
  onSelect: (id: string) => void;
  onToggle: (id: string) => void;
}) {
  const active = activeId === observation.id;
  const hasChildren = observation.children.length > 0;
  const open = !collapsed[observation.id];
  const bar = waterfallShare(observation.offsetMs, observation.latencyMs, totalMs);

  return (
    <li>
      <div className="flex items-center gap-1">
        {hasChildren ? (
          <button
            type="button"
            onClick={() => onToggle(observation.id)}
            className="shrink-0 rounded p-0.5 text-stone-400 hover:bg-stone-100 hover:text-stone-700 dark:hover:bg-stone-800"
            aria-label={open ? '收起' : '展开'}
            style={{ marginLeft: depth * 12 }}
          >
            {open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
          </button>
        ) : (
          <span className="w-4 shrink-0" style={{ marginLeft: depth * 12 }} />
        )}
        <button
          type="button"
          onClick={() => onSelect(observation.id)}
          className={`flex min-w-0 flex-1 items-center gap-2 rounded-lg px-2 py-1.5 text-left ${
            active ? 'bg-stone-900 text-white dark:bg-stone-100 dark:text-stone-900' : 'hover:bg-stone-50 dark:hover:bg-stone-800/70'
          }`}
        >
          <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${statusDot(observation.status)}`} />
          <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${active ? 'bg-white/15' : typeBadge(observation.type)}`}>
            {observationTypeLabel(observation.type)}
          </span>
          <span className="min-w-0 flex-1 truncate text-xs">{observation.title}</span>
          <span className="relative hidden h-1.5 w-16 shrink-0 overflow-hidden rounded-full bg-stone-200/80 dark:bg-stone-700 sm:block">
            <span
              className={`absolute top-0 h-full rounded-full ${
                observation.type === 'generation' ? 'bg-violet-400' : observation.type === 'retriever' ? 'bg-sky-400' : 'bg-stone-400'
              }`}
              style={{ left: `${bar.left * 100}%`, width: `${bar.width * 100}%` }}
            />
          </span>
          <span className={`w-14 shrink-0 text-right text-[11px] tabular-nums ${active ? 'opacity-70' : 'text-stone-400'}`}>
            {observation.latencyLabel}
          </span>
        </button>
      </div>
      {hasChildren && open && (
        <ol className="mt-0.5 space-y-0.5">
          {observation.children.map(child => (
            <TreeRow
              key={child.id}
              observation={child}
              activeId={activeId}
              depth={depth + 1}
              totalMs={totalMs}
              collapsed={collapsed}
              onSelect={onSelect}
              onToggle={onToggle}
            />
          ))}
        </ol>
      )}
    </li>
  );
}

function ObservationPane({
  observation,
  tab,
  onTab,
}: {
  observation: TraceObservation;
  tab: DetailTab;
  onTab: (tab: DetailTab) => void;
}) {
  const tabs: { id: DetailTab; label: string; hidden?: boolean }[] = [
    { id: 'fields', label: '字段', hidden: observation.fields.length === 0 },
    { id: 'input', label: '输入' },
    { id: 'output', label: '输出' },
    { id: 'documents', label: `文档（${observation.documents.length}）`, hidden: observation.documents.length === 0 },
  ];

  return (
    <section className="surface-card flex min-h-[20rem] flex-col overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-stone-100 px-3 py-2 dark:border-stone-800">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${typeBadge(observation.type)}`}>
              {observationTypeLabel(observation.type)}
            </span>
            <h2 className="truncate text-sm text-stone-900 dark:text-white">{observation.title}</h2>
          </div>
          <p className="mt-1 text-[11px] text-stone-400">
            {observation.latencyLabel}
            {observation.summary ? ` · ${observation.summary}` : ''}
          </p>
        </div>
        <div className="flex gap-1">
          {tabs.filter(item => !item.hidden).map(item => (
            <button
              key={item.id}
              type="button"
              onClick={() => onTab(item.id)}
              className={`rounded px-2 py-1 text-xs ${
                tab === item.id
                  ? 'bg-stone-900 text-white dark:bg-stone-100 dark:text-stone-900'
                  : 'text-stone-500 hover:bg-stone-100 dark:hover:bg-stone-800'
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>
      </div>
      <div className="flex-1 overflow-y-auto p-3">
        {tab === 'documents' ? (
          <DocumentList documents={observation.documents} />
        ) : tab === 'fields' ? (
          <FieldList fields={observation.fields} />
        ) : (
          <pre className="whitespace-pre-wrap break-words font-mono text-xs leading-5 text-stone-700 dark:text-stone-200">
            {tab === 'input' ? observation.input : observation.output}
          </pre>
        )}
      </div>
    </section>
  );
}

function FieldList({ fields }: { fields: TraceField[] }) {
  if (fields.length === 0) {
    return <p className="text-sm text-stone-400">这一步没有结构化字段，看输入 / 输出。</p>;
  }
  return (
    <dl className="grid grid-cols-[7.5rem_minmax(0,1fr)] gap-x-3 gap-y-2.5">
      {fields.map(field => (
        <div key={`${field.label}-${field.value}`} className="contents">
          <dt className="pt-0.5 text-xs text-stone-400">{field.label}</dt>
          <dd className="whitespace-pre-wrap break-words text-sm leading-6 text-stone-800 dark:text-stone-100">
            {field.value}
          </dd>
        </div>
      ))}
    </dl>
  );
}

function DocumentList({ documents }: { documents: TraceDocument[] }) {
  if (documents.length === 0) {
    return <p className="text-sm text-stone-400">这一步没有文档输出。</p>;
  }
  return (
    <ol className="space-y-2">
      {documents.map(doc => (
        <li key={`${doc.rank}-${doc.content.slice(0, 24)}`} className="rounded-lg border border-stone-100 p-3 dark:border-stone-800">
          <div className="flex flex-wrap items-center gap-2 text-[11px] text-stone-500">
            <span className="font-mono">#{doc.rank}</span>
            {doc.score != null && <span>检索分 {doc.score.toFixed(2)}</span>}
            {doc.rerankScore != null && <span>精排分 {doc.rerankScore.toFixed(2)}</span>}
            {doc.source && <span className="truncate">{doc.source}</span>}
            <span className={`ml-auto rounded-full px-2 py-0.5 ${
              !doc.valid ? 'bg-rose-100 text-rose-700 dark:bg-rose-950/40 dark:text-rose-200'
                : doc.cited ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-200'
                  : 'bg-stone-100 text-stone-500 dark:bg-stone-800'
            }`}>
              {!doc.valid ? '无效' : doc.cited ? '已引用' : '未引用'}
            </span>
            {doc.junk && <span className="rounded-full bg-amber-100 px-2 py-0.5 text-amber-800 dark:bg-amber-950/40 dark:text-amber-200">目录噪声</span>}
          </div>
          <p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-stone-700 dark:text-stone-200">
            {doc.content || '（空片段）'}
          </p>
        </li>
      ))}
    </ol>
  );
}
