import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  ChevronRight,
  ClipboardCopy,
  Play,
  Plus,
  RotateCcw,
  Trash2,
} from 'lucide-react';
import PageHeader from '../components/ui/PageHeader';
import { getErrorMessage } from '../api/request';
import { knowledgeBaseApi, type KnowledgeBaseItem } from '../api/knowledgebase';
import {
  evalApi,
  type EvalRunRequest,
  type EvalRunResponse,
  type EvalRunSummary,
  type IntentCase,
  type JudgeCase,
  type RagEvalItem,
} from '../api/eval';
import type { FlowTone } from './ragTraceFlow';
import {
  EVAL_GATES,
  type EvalGateId,
  type EvalRunScope,
  buildStandardRagMetrics,
  firstAttentionGate,
  layersForScope,
  pickDefaultEvalKbIds,
  runScopeLabel,
  formatEvalReport,
  gateTone,
  humanizeFailures,
  intentCaseFromGate,
  intentGateValue,
  intentLabel,
  INTENT_OPTIONS,
  toRelatedOnlyIntentCase,
  metricLabel,
  num,
  pct,
  qualityGateSummary,
  type StandardRagMetricCard,
} from './evalRunDisplay';

const INPUT_CLASS = 'dark-input w-full px-3 py-2 text-sm';

const DEFAULT_INTENT: IntentCase[] = [
  { question: '讲讲 JVM 垃圾回收原理', expectedRelated: true },
  { question: '这个项目的缓存是怎么设计的？', expectedRelated: true },
  { question: '今天天气怎么样', expectedIntent: 'OFF_TOPIC', expectedRelated: false },
  { question: '帮我点一份外卖', expectedIntent: 'OFF_TOPIC', expectedRelated: false },
];

const DEFAULT_JUDGE: JudgeCase[] = [
  {
    question: '什么是缓存穿透，如何防止',
    answer: '缓存穿透是查缓存和库里都不存在的数据，每次都打到数据库。可以用布隆过滤器拦住不存在的 key，并对空结果做短 TTL 空值缓存。',
    referenceAnswer: '查询不存在的数据；布隆过滤器；缓存空值。',
    context: '缓存穿透指查询不存在的数据导致请求打到数据库。常见防法是布隆过滤器和空值缓存。',
    minOverallScore: 0.75,
  },
  {
    question: '常见的垃圾回收器有哪些',
    answer: '常见的有 Serial、ParNew、Parallel，以及 CMS、G1、ZGC、Shenandoah。CMS 是分代收集器，G1 和 ZGC 是分区收集器。',
    referenceAnswer: 'CMS、G1、ZGC。',
    context: 'JVM 垃圾收集器主要分分代收集器和分区收集器，分代代表是 CMS，分区代表是 G1 和 ZGC。',
    minOverallScore: 0.75,
  },
];

const DEFAULT_RAG: RagEvalItem[] = [
  {
    question: '什么是缓存穿透，如何防止',
    expectedKeywords: ['不存在|数据库中不存在', '布隆过滤器|bloom', '空值缓存|缓存空值|空对象'],
  },
  {
    question: '常见的垃圾回收器有哪些',
    expectedKeywords: ['CMS', 'G1|ZGC|Shenandoah'],
  },
];

function toneBox(tone: FlowTone): string {
  if (tone === 'fail') return 'border-rose-200 bg-rose-50 text-rose-800 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-200';
  if (tone === 'warn') return 'border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200';
  if (tone === 'skip') return 'border-stone-200 bg-stone-50 text-stone-500 dark:border-stone-800 dark:bg-stone-900 dark:text-stone-400';
  return 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950/30 dark:text-emerald-200';
}

function nodeClass(tone: FlowTone, selected: boolean): string {
  const ring = selected ? 'ring-2 ring-primary-500 border-primary-400' : 'border-stone-200 dark:border-stone-800';
  const fill = selected
    ? 'bg-primary-50 dark:bg-primary-950/40'
    : 'bg-white dark:bg-stone-900 hover:border-primary-300 dark:hover:border-primary-800';
  const bar = tone === 'fail'
    ? 'before:bg-rose-500'
    : tone === 'warn'
      ? 'before:bg-amber-500'
      : tone === 'skip'
        ? 'before:bg-stone-300'
        : 'before:bg-emerald-500';
  return `${ring} ${fill} ${bar} before:absolute before:inset-y-0 before:left-0 before:w-1 before:rounded-l-xl`;
}

function badgeClass(tone: FlowTone): string {
  if (tone === 'fail') return 'bg-rose-100 text-rose-700 dark:bg-rose-950/40 dark:text-rose-200';
  if (tone === 'warn') return 'bg-amber-100 text-amber-700 dark:bg-amber-950/40 dark:text-amber-200';
  if (tone === 'ok') return 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-200';
  return 'bg-stone-100 text-stone-600 dark:bg-stone-800 dark:text-stone-300';
}

function gateStatusLabel(tone: FlowTone, hasResult: boolean): string {
  if (!hasResult) return '待运行';
  if (tone === 'ok') return '通过';
  if (tone === 'fail') return '未通过';
  if (tone === 'warn') return '未跑完';
  return '未纳入';
}

export default function EvalRunPage() {
  const [title, setTitle] = useState('默认例题');
  const [baselineKey, setBaselineKey] = useState('bagua-faq-baseline');
  const [updateBaseline, setUpdateBaseline] = useState(false);
  const [regressionThreshold, setRegressionThreshold] = useState(0.03);
  const [intentCases, setIntentCases] = useState<IntentCase[]>(DEFAULT_INTENT);
  const [judgeCases, setJudgeCases] = useState<JudgeCase[]>(DEFAULT_JUDGE);
  const [ragKbIds, setRagKbIds] = useState<number[]>([]);
  const [ragK, setRagK] = useState(5);
  const [ragItems, setRagItems] = useState<RagEvalItem[]>(DEFAULT_RAG);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<EvalRunResponse | null>(null);
  const [copyHint, setCopyHint] = useState<string | null>(null);
  const [recentRuns, setRecentRuns] = useState<EvalRunSummary[]>([]);
  const [activeGate, setActiveGate] = useState<EvalGateId>('retrieve');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [runningScope, setRunningScope] = useState<EvalRunScope | null>(null);
  const resultRef = useRef<HTMLDivElement | null>(null);

  const loadExample = () => {
    setTitle('默认例题');
    setBaselineKey('bagua-faq-baseline');
    setUpdateBaseline(false);
    setRegressionThreshold(0.03);
    setIntentCases(DEFAULT_INTENT);
    setJudgeCases(DEFAULT_JUDGE);
    setRagItems(DEFAULT_RAG);
    setRagKbIds(pickDefaultEvalKbIds(knowledgeBases));
    setActiveGate('retrieve');
    setError(null);
    setCopyHint(null);
  };

  useEffect(() => {
    knowledgeBaseApi
      .getAllKnowledgeBases('time', 'VECTOR_STORED')
      .then((list) => {
        setKnowledgeBases(list);
        setRagKbIds((prev) => (prev.length === 0 ? pickDefaultEvalKbIds(list) : prev));
      })
      .catch(err => console.error('Failed to load knowledge bases:', err));
  }, []);

  useEffect(() => {
    evalApi.list(20).then(setRecentRuns).catch(() => setRecentRuns([]));
  }, []);

  const run = async (scope: EvalRunScope) => {
    setError(null);
    setResult(null);
    setCopyHint(null);
    const layers = layersForScope(scope);
    const body: EvalRunRequest = {
      title: title.trim() || undefined,
      baselineKey: baselineKey.trim() || undefined,
      updateBaseline,
      regressionThreshold,
    };
    if (layers.intent) {
      const cases = intentCases.filter(item => item.question.trim()).map(toRelatedOnlyIntentCase);
      if (cases.length > 0) {
        body.intentCases = cases;
      } else if (scope === 'intent') {
        setError('意图门至少留一题。');
        return;
      }
    }
    if (layers.judge) {
      const cases = judgeCases.filter(item => item.question.trim() && item.answer.trim());
      if (cases.length > 0) {
        body.judgeCases = cases;
      } else if (scope === 'judge') {
        setError('生成评测需要问题和待打分的回答。');
        return;
      }
    }
    if (layers.retrieve) {
      const items = ragItems.filter(item => item.question.trim());
      if (items.length === 0 && scope === 'retrieve') {
        setError('检索评测至少留一题。');
        return;
      }
      if (items.length > 0 && ragKbIds.length === 0) {
        setError('只评检索时，先选一份已向量化的资料。');
        return;
      }
      if (items.length > 0 && ragKbIds.length > 0) {
        body.rag = { knowledgeBaseIds: ragKbIds, k: ragK, items };
      }
    }
    if (!body.intentCases?.length && !body.judgeCases?.length && !body.rag) {
      setError('这一层没有可跑的例题。默认例题可以直接运行。');
      return;
    }
    setRunning(true);
    setRunningScope(scope);
    try {
      const next = await evalApi.run(body);
      setResult(next);
      setActiveGate(scope === 'all' ? firstAttentionGate(next) : scope);
      setRecentRuns(await evalApi.list(20).catch(() => recentRuns));
      requestAnimationFrame(() => resultRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
    } catch (err) {
      setError(getErrorMessage(err, '评测运行失败'));
    } finally {
      setRunning(false);
      setRunningScope(null);
    }
  };

  const copyReport = async () => {
    if (!result) return;
    try {
      await navigator.clipboard.writeText(formatEvalReport(result));
      setCopyHint('报告已复制，可以贴进笔记。');
    } catch {
      setCopyHint('复制失败，请手动选中下方报告文本。');
    }
  };

  const openRecent = async (runId: string) => {
    try {
      const next = await evalApi.get(runId);
      setResult(next);
      setActiveGate(firstAttentionGate(next));
      setError(null);
      requestAnimationFrame(() => resultRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
    } catch {
      setError('加载历史评测失败');
    }
  };

  const gateOptions = { hasRetrieveCases: ragItems.some(item => item.question.trim()), hasKnowledgeBase: ragKbIds.length > 0 };
  const currentGate = EVAL_GATES.find(gate => gate.id === activeGate) ?? EVAL_GATES[0];
  const diagnosis = useMemo(() => qualityGateSummary(result?.qualityGate), [result]);

  return (
    <div className="mx-auto max-w-6xl space-y-5">
      <PageHeader
        eyebrow="知识库问答"
        title="RAG 效果评测"
        description="默认例题是 Redis 缓存穿透、JVM 收集器这类八股，不是简历里的客服项目。勾面渣 Redis / JVM 库，三层可以分开测。"
        action={(
          <div className="flex flex-wrap gap-2">
            <button type="button" onClick={loadExample} className="btn-secondary inline-flex items-center gap-2 px-4 py-2 text-sm">
              <RotateCcw className="h-4 w-4" />
              恢复默认例题
            </button>
            <button
              type="button"
              onClick={() => void run('all')}
              disabled={running}
              className="btn-secondary inline-flex items-center gap-2 px-4 py-2 text-sm disabled:opacity-50"
            >
              {running && runningScope === 'all' ? '正在评测…' : '三层一起评'}
            </button>
          </div>
        )}
      />

      <section className="surface-card overflow-hidden">
        <div className="border-b border-stone-200 px-5 py-3 dark:border-stone-800">
          <p className="text-xs font-medium text-primary-700 dark:text-primary-300">怎么用</p>
          <ol className="mt-2 grid gap-2 text-sm leading-6 text-stone-600 dark:text-stone-300 sm:grid-cols-3">
            <li><span className="font-medium text-stone-900 dark:text-white">1.</span> 点下面一层（意图 / 检索 / 生成）</li>
            <li><span className="font-medium text-stone-900 dark:text-white">2.</span> 例题已填好，检索层先选一份资料</li>
            <li><span className="font-medium text-stone-900 dark:text-white">3.</span> 点「只评这一层」，看这一层的分数</li>
          </ol>
        </div>
        <ol className="grid gap-0 md:grid-cols-3">
          <li className="border-b border-stone-200 p-5 md:border-b-0 md:border-r dark:border-stone-800">
            <p className="text-[11px] font-medium tracking-wide text-stone-400">可以单独测</p>
            <p className="mt-1 text-sm font-semibold text-stone-900 dark:text-white">意图门</p>
            <p className="mt-2 text-xs leading-5 text-stone-500">八股、算法、项目提问该查资料；闲聊不该查。不需要知识库。</p>
          </li>
          <li className="border-b border-stone-200 p-5 md:border-b-0 md:border-r dark:border-stone-800">
            <p className="text-[11px] font-medium tracking-wide text-stone-400">可以单独测</p>
            <p className="mt-1 text-sm font-semibold text-stone-900 dark:text-white">检索 · Context Precision / Recall</p>
            <p className="mt-2 text-xs leading-5 text-stone-500">在你选的资料里找关键点。要先有已向量化的文档。</p>
          </li>
          <li className="p-5">
            <p className="text-[11px] font-medium tracking-wide text-stone-400">可以单独测</p>
            <p className="mt-1 text-sm font-semibold text-stone-900 dark:text-white">生成 · Faithfulness / Relevancy</p>
            <p className="mt-2 text-xs leading-5 text-stone-500">给填好的回答打分。不需要知识库，也不跑检索。</p>
          </li>
        </ol>
        <div className="border-t border-stone-200 px-5 py-3 text-xs leading-5 text-stone-400 dark:border-stone-800">
          想看一次真实提问怎么走，去
          {' '}
          <Link to="/rag-traces" className="text-primary-700 underline underline-offset-2 dark:text-primary-300">问答过程回放</Link>
          。100 题离线黄金集在仓库 <code>eval/rag</code>，不在本页出分。
        </div>
      </section>

      {knowledgeBases.length === 0 && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200">
          还没有已向量化的资料，检索门不会跑。
          {' '}
          <Link to="/knowledgebase" className="underline underline-offset-2">先去上传并处理文档</Link>
          ，处理完成后回到这里。意图分类和回答打分仍可运行。
        </div>
      )}

      {error && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 dark:border-rose-800 dark:bg-rose-900/20 dark:text-rose-300">
          {error}
        </div>
      )}

      <section className="space-y-3">
        <div className="flex items-end justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-stone-900 dark:text-white">选一层再评</h2>
            <p className="mt-1 text-xs text-stone-500">
              {activeGate === 'baseline'
                ? '回归对比不是单独一层评测，先评其他层再回来看涨跌。'
                : `当前选中「${currentGate.title}」，可以只评这一层。`}
            </p>
          </div>
        </div>
        <ol className="grid gap-2 md:grid-cols-4">
          {EVAL_GATES.map((gate, index) => {
            const tone = gateTone(gate.id, result, gateOptions);
            return (
              <li key={gate.id} className="flex items-stretch gap-1">
                <button
                  type="button"
                  onClick={() => setActiveGate(gate.id)}
                  className={`relative min-h-[7.5rem] flex-1 rounded-xl border px-3 py-2.5 text-left transition-colors ${nodeClass(tone, activeGate === gate.id)}`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <p className="text-[11px] font-medium text-stone-400">第 {gate.order} 步</p>
                    <span className={`rounded-full px-2 py-0.5 text-[11px] ${badgeClass(tone)}`}>
                      {gateStatusLabel(tone, Boolean(result))}
                    </span>
                  </div>
                  <p className="mt-1 text-sm font-semibold text-stone-900 dark:text-white">{gate.title}</p>
                  <p className="mt-1 line-clamp-2 text-xs leading-5 text-stone-500 dark:text-stone-400">{gate.summary}</p>
                </button>
                {index < EVAL_GATES.length - 1 && (
                  <ChevronRight className="mt-10 hidden h-4 w-4 shrink-0 text-stone-300 lg:block dark:text-stone-600" aria-hidden />
                )}
              </li>
            );
          })}
        </ol>
      </section>

      <div ref={resultRef}>
        {running && (
          <section className="surface-card px-5 py-8 text-center">
            <p className="text-sm font-medium text-stone-800 dark:text-stone-100">
              {runningScope ? `${runScopeLabel(runningScope)}进行中` : '正在评测'}
            </p>
            <p className="mt-2 text-sm text-stone-500">
              {runningScope === 'retrieve'
                ? '只跑真实检索，不评意图和生成。'
                : runningScope === 'judge'
                  ? '只给填好的回答打分，不跑检索。'
                  : runningScope === 'intent'
                    ? '只判断该不该查资料。'
                    : '会跑意图、检索和生成三层。'}
            </p>
          </section>
        )}

        {result && !running && (
          <EvalResult
            result={result}
            diagnosis={diagnosis}
            reportText={formatEvalReport(result)}
            copyHint={copyHint}
            onCopy={() => void copyReport()}
            onSelectGate={setActiveGate}
          />
        )}
      </div>

      <section className="surface-card space-y-4 p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <p className="text-xs font-medium text-primary-700 dark:text-primary-300">当前：{currentGate.title}</p>
            <p className="mt-1 text-sm leading-6 text-stone-600 dark:text-stone-300">{currentGate.purpose}</p>
            <p className="mt-2 text-xs leading-5 text-stone-400">{currentGate.how}</p>
          </div>
          {activeGate !== 'baseline' && (
            <button
              type="button"
              onClick={() => void run(activeGate)}
              disabled={running}
              className="btn-primary inline-flex shrink-0 items-center gap-2 px-4 py-2 text-sm disabled:opacity-50"
            >
              <Play className="h-4 w-4" />
              {running && runningScope === activeGate ? '正在评…' : runScopeLabel(activeGate)}
            </button>
          )}
        </div>

        {activeGate === 'intent' && (
          <IntentEditor cases={intentCases} onChange={setIntentCases} result={result} />
        )}
        {activeGate === 'retrieve' && (
          <RetrieveEditor
            items={ragItems}
            onChange={setRagItems}
            knowledgeBases={knowledgeBases}
            ragKbIds={ragKbIds}
            onKbIdsChange={setRagKbIds}
            ragK={ragK}
            onKChange={setRagK}
            result={result}
          />
        )}
        {activeGate === 'judge' && (
          <JudgeEditor cases={judgeCases} onChange={setJudgeCases} result={result} />
        )}
        {activeGate === 'baseline' && (
          <BaselinePanel
            result={result}
            baselineKey={baselineKey}
            onBaselineKeyChange={setBaselineKey}
            regressionThreshold={regressionThreshold}
            onThresholdChange={setRegressionThreshold}
            updateBaseline={updateBaseline}
            onUpdateBaselineChange={setUpdateBaseline}
          />
        )}
      </section>

      {recentRuns.length > 0 && (
        <section className="surface-card space-y-3 p-5">
          <div className="flex items-center justify-between gap-2">
            <h2 className="text-sm font-semibold text-stone-900 dark:text-white">最近一次评测</h2>
            <span className="text-xs text-stone-400">点开可回看，门槛来自后端配置</span>
          </div>
          <div className="grid gap-2 md:grid-cols-2">
            {recentRuns.slice(0, 6).map(item => (
              <button
                key={item.runId}
                type="button"
                onClick={() => void openRecent(item.runId)}
                className="rounded-xl border border-stone-200 p-3 text-left transition hover:border-primary-300 dark:border-stone-800 dark:hover:border-primary-800"
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate text-sm text-stone-700 dark:text-stone-200">{item.title}</span>
                  <span className={`rounded-full px-2 py-0.5 text-xs ${item.qualityGate?.passed ? badgeClass('ok') : badgeClass('warn')}`}>
                    {item.qualityGate?.passed ? '过门' : '需关注'}
                  </span>
                </div>
                <p className="mt-1 text-xs text-stone-400">综合分 {pct(item.overallScore)} · {item.createdAt}</p>
                {(item.qualityGate?.failures?.length ?? 0) > 0 && (
                  <p className="mt-1 line-clamp-2 text-xs text-amber-700 dark:text-amber-300">
                    {humanizeFailures(item.qualityGate.failures).join('；')}
                  </p>
                )}
              </button>
            ))}
          </div>
        </section>
      )}

      <section className="surface-card p-5">
        <button
          type="button"
          onClick={() => setShowAdvanced(open => !open)}
          className="flex w-full items-center justify-between text-left"
        >
          <div>
            <h2 className="text-sm font-semibold text-stone-900 dark:text-white">比较设置</h2>
            <p className="mt-1 text-xs text-stone-500">运行名称、比较组、允许多大波动。日常用默认即可。</p>
          </div>
          <span className="text-xs text-primary-700 dark:text-primary-300">{showAdvanced ? '收起' : '展开'}</span>
        </button>
        {showAdvanced && (
          <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
            <label className="space-y-1 text-sm text-stone-600 dark:text-stone-300">
              <span>运行名称</span>
              <input className={INPUT_CLASS} value={title} onChange={event => setTitle(event.target.value)} />
            </label>
            <label className="space-y-1 text-sm text-stone-600 dark:text-stone-300">
              <span>比较组</span>
              <input className={INPUT_CLASS} value={baselineKey} onChange={event => setBaselineKey(event.target.value)} />
              <span className="block text-xs text-stone-400">同一组才会和上次保存的基准比。</span>
            </label>
            <label className="space-y-1 text-sm text-stone-600 dark:text-stone-300">
              <span>允许波动</span>
              <input
                type="number"
                step="0.01"
                className={INPUT_CLASS}
                value={regressionThreshold}
                onChange={event => setRegressionThreshold(parseFloat(event.target.value) || 0)}
              />
              <span className="block text-xs text-stone-400">0.03 表示比基准低超过 3 个百分点才算退步。</span>
            </label>
            <label className="flex items-center gap-2 self-end pb-2 text-sm text-stone-600 dark:text-stone-300">
              <input type="checkbox" checked={updateBaseline} onChange={event => setUpdateBaseline(event.target.checked)} />
              <span>把这次结果存成以后的比较基准</span>
            </label>
          </div>
        )}
      </section>
    </div>
  );
}

function IntentEditor({
  cases,
  onChange,
  result,
}: {
  cases: IntentCase[];
  onChange: (next: IntentCase[]) => void;
  result: EvalRunResponse | null;
}) {
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <p className="text-sm font-medium text-stone-800 dark:text-stone-100">例题</p>
        <button
          type="button"
          onClick={() => onChange([...cases, intentCaseFromGate('', 'RELATED')])}
          className="inline-flex items-center gap-1 text-sm text-primary-700 dark:text-primary-300"
        >
          <Plus className="h-4 w-4" /> 加一题
        </button>
      </div>
      {result?.intent && (
        <p className="text-sm text-stone-500">
          这轮 {result.intent.correct}/{result.intent.total} 题分类正确，准确率 {pct(result.intent.accuracy)}。
        </p>
      )}
      <div className="space-y-2">
        {cases.map((item, index) => {
          const scored = result?.intent?.items[index];
          return (
            <div key={index} className="rounded-xl border border-stone-200 p-3 dark:border-stone-800">
              <div className="flex flex-wrap items-start gap-2">
                <input
                  className={`${INPUT_CLASS} min-w-[240px] flex-1`}
                  placeholder="问题，例如：讲讲 JVM 垃圾回收原理"
                  value={item.question}
                  onChange={event => onChange(cases.map((row, rowIndex) => (
                    rowIndex === index ? { ...row, question: event.target.value } : row
                  )))}
                />
                <select
                  className={`${INPUT_CLASS} w-64`}
                  value={intentGateValue(item.expectedIntent, item.expectedRelated)}
                  onChange={event => onChange(cases.map((row, rowIndex) => (
                    rowIndex === index
                      ? intentCaseFromGate(row.question, event.target.value as 'RELATED' | 'OFF_TOPIC')
                      : row
                  )))}
                >
                  {INTENT_OPTIONS.map(option => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
                <button type="button" onClick={() => onChange(cases.filter((_, rowIndex) => rowIndex !== index))} className="mt-2 text-stone-400 hover:text-rose-500">
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
              {scored && (
                <p className={`mt-2 text-xs ${scored.correct ? 'text-emerald-600' : 'text-rose-600'}`}>
                  {scored.correct ? '分类正确' : '分类错了'}
                  ：系统看成「{intentLabel(scored.actualIntent, scored.actualRelated)}」，期望是「{intentLabel(scored.expectedIntent, scored.expectedRelated)}」
                </p>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function RetrieveEditor({
  items,
  onChange,
  knowledgeBases,
  ragKbIds,
  onKbIdsChange,
  ragK,
  onKChange,
  result,
}: {
  items: RagEvalItem[];
  onChange: (next: RagEvalItem[]) => void;
  knowledgeBases: KnowledgeBaseItem[];
  ragKbIds: number[];
  onKbIdsChange: (next: number[]) => void;
  ragK: number;
  onKChange: (next: number) => void;
  result: EvalRunResponse | null;
}) {
  const toggleKb = (id: number) => {
    onKbIdsChange(ragKbIds.includes(id) ? ragKbIds.filter(item => item !== id) : [...ragKbIds, id]);
  };

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="space-y-2">
          <p className="text-sm font-medium text-stone-800 dark:text-stone-100">在哪些资料里检索</p>
          {knowledgeBases.length === 0 ? (
            <p className="text-sm text-amber-700 dark:text-amber-300">没有已向量化的资料可选。八股题需要面渣 Redis / JVM，不要只用简历。</p>
          ) : (
            <>
              <ul className="max-h-40 space-y-1 overflow-auto rounded-xl border border-stone-200 p-2 dark:border-stone-800">
                {knowledgeBases.map(kb => (
                  <li key={kb.id}>
                    <label className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-sm hover:bg-stone-50 dark:hover:bg-stone-800/60">
                      <input type="checkbox" checked={ragKbIds.includes(kb.id)} onChange={() => toggleKb(kb.id)} />
                      <span className="truncate text-stone-700 dark:text-stone-200">{kb.name}</span>
                    </label>
                  </li>
                ))}
              </ul>
              <p className="text-xs text-stone-400">默认勾面渣 Redis、JVM。简历 / 客服那份对不上这两道八股题。</p>
            </>
          )}
        </div>
        <label className="space-y-1 text-sm text-stone-600 dark:text-stone-300">
          <span>看前几条</span>
          <input
            type="number"
            min={1}
            className={INPUT_CLASS}
            value={ragK}
            onChange={event => onKChange(parseInt(event.target.value, 10) || 5)}
          />
          <span className="block text-xs text-stone-400">默认看前 5 条。右侧填参考答案要点，同一说法的别名用 |。</span>
        </label>
      </div>

      {result?.rag && (
        <p className="text-sm text-stone-500">
          Context Precision {pct(result.rag.retrievalPrecision)} · Context Recall {pct(result.rag.retrievalRecall)}
          {' · '}Hit@{result.rag.k} {pct(result.rag.hitRate)} · MRR {num(result.rag.mrr)} · nDCG {num(result.rag.ndcg)}
        </p>
      )}

      <div className="flex items-center justify-between">
        <p className="text-sm font-medium text-stone-800 dark:text-stone-100">八股题目与参考答案</p>
        <button
          type="button"
          onClick={() => onChange([...items, { question: '', expectedKeywords: [] }])}
          className="inline-flex items-center gap-1 text-sm text-primary-700 dark:text-primary-300"
        >
          <Plus className="h-4 w-4" /> 加一题
        </button>
      </div>
      <div className="space-y-2">
        {items.map((item, index) => {
          const scored = result?.rag?.items[index];
          return (
            <div key={index} className="rounded-xl border border-stone-200 p-3 dark:border-stone-800">
              <div className="flex items-start gap-2">
                <div className="min-w-0 flex-1 space-y-2">
                  <label className="block space-y-1">
                    <span className="text-xs text-stone-400">题目</span>
                    <input
                      className={INPUT_CLASS}
                      placeholder="例如：什么是缓存穿透，如何防止"
                      value={item.question}
                      onChange={event => onChange(items.map((row, rowIndex) => (
                        rowIndex === index ? { ...row, question: event.target.value } : row
                      )))}
                    />
                  </label>
                  <label className="block space-y-1">
                    <span className="text-xs text-stone-400">参考答案要点（逗号分隔，同一说法用 |）</span>
                    <input
                      className={INPUT_CLASS}
                      placeholder="例如：不存在, 布隆过滤器, 空值缓存"
                      value={(item.expectedKeywords ?? []).join(', ')}
                      onChange={event => onChange(items.map((row, rowIndex) => (
                        rowIndex === index
                          ? { ...row, expectedKeywords: event.target.value.split(',').map(part => part.trim()).filter(Boolean) }
                          : row
                      )))}
                    />
                  </label>
                </div>
                <button type="button" onClick={() => onChange(items.filter((_, rowIndex) => rowIndex !== index))} className="mt-6 text-stone-400 hover:text-rose-500">
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
              {scored && (
                <div className="mt-2 space-y-1 text-xs">
                  <p className={scored.hit ? 'text-emerald-600' : 'text-rose-600'}>
                    {scored.hit ? `命中，第一次出现在第 ${scored.firstHitRank} 条` : '前几条里没找到这些关键点'}
                    {scored.matchedKeywords?.length ? ` · 命中 ${scored.matchedKeywords.join('、')}` : ''}
                    {scored.missingKeywords?.length ? ` · 缺 ${scored.missingKeywords.join('、')}` : ''}
                  </p>
                  {(scored.retrievedSegments ?? []).slice(0, 3).map(segment => (
                    <p key={`${scored.question}-${segment.rank}`} className="line-clamp-2 text-stone-500">
                      #{segment.rank} {segment.snippet || '(无摘要)'}
                    </p>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>
      {items.length > 0 && ragKbIds.length === 0 && (
        <p className="text-xs text-amber-700 dark:text-amber-300">还没选资料，这组检索例题不会运行。</p>
      )}
    </div>
  );
}

function JudgeEditor({
  cases,
  onChange,
  result,
}: {
  cases: JudgeCase[];
  onChange: (next: JudgeCase[]) => void;
  result: EvalRunResponse | null;
}) {
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <p className="text-sm font-medium text-stone-800 dark:text-stone-100">八股题目与完整答案</p>
        <button
          type="button"
          onClick={() => onChange([...cases, { question: '', answer: '', minOverallScore: 0.75 }])}
          className="inline-flex items-center gap-1 text-sm text-primary-700 dark:text-primary-300"
        >
          <Plus className="h-4 w-4" /> 加一条
        </button>
      </div>
      {result?.judge && (
        <p className="text-sm text-stone-500">
          Faithfulness {pct(result.judge.averageAccuracy)} · Relevancy {pct(result.judge.averageRelevance)}
          {' · '}Correctness {pct(result.judge.averageOverall)} · {result.judge.passed}/{result.judge.total} 条达标
        </p>
      )}
      {cases.map((item, index) => {
        const scored = result?.judge?.items[index];
        return (
          <div key={index} className="space-y-2 rounded-xl border border-stone-200 p-3 dark:border-stone-800">
            <div className="flex items-center justify-between">
              <span className="text-xs text-stone-400">回答 {index + 1}</span>
              <button type="button" onClick={() => onChange(cases.filter((_, rowIndex) => rowIndex !== index))} className="text-stone-400 hover:text-rose-500">
                <Trash2 className="h-4 w-4" />
              </button>
            </div>
            <input
              className={INPUT_CLASS}
              placeholder="问题"
              value={item.question}
              onChange={event => onChange(cases.map((row, rowIndex) => (
                rowIndex === index ? { ...row, question: event.target.value } : row
              )))}
            />
            <textarea
              className={`${INPUT_CLASS} h-16 resize-none`}
              placeholder="检索上下文 c(q)，Faithfulness 的事实边界"
              value={item.context ?? ''}
              onChange={event => onChange(cases.map((row, rowIndex) => (
                rowIndex === index ? { ...row, context: event.target.value } : row
              )))}
            />
            <textarea
              className={`${INPUT_CLASS} h-20 resize-none`}
              placeholder="生成答案 a"
              value={item.answer}
              onChange={event => onChange(cases.map((row, rowIndex) => (
                rowIndex === index ? { ...row, answer: event.target.value } : row
              )))}
            />
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <input
                className={INPUT_CLASS}
                placeholder="参考答案（Correctness）"
                value={item.referenceAnswer ?? ''}
                onChange={event => onChange(cases.map((row, rowIndex) => (
                  rowIndex === index ? { ...row, referenceAnswer: event.target.value } : row
                )))}
              />
              <input
                type="number"
                step="0.05"
                className={INPUT_CLASS}
                placeholder="最低总分，例如 0.75"
                value={item.minOverallScore ?? ''}
                onChange={event => onChange(cases.map((row, rowIndex) => (
                  rowIndex === index ? { ...row, minOverallScore: parseFloat(event.target.value) || undefined } : row
                )))}
              />
            </div>
            {scored && (
              <p className={`text-xs ${scored.passed ? 'text-emerald-600' : 'text-rose-600'}`}>
                {scored.passed ? '达标' : '未达标'}
                {' · '}Faithfulness {num(scored.accuracy)}
                {' · '}Relevancy {num(scored.relevance)}
                {' · '}Correctness {num(scored.overall)}
                {scored.reason ? ` · ${scored.reason}` : ''}
              </p>
            )}
          </div>
        );
      })}
    </div>
  );
}

function BaselinePanel({
  result,
  baselineKey,
  onBaselineKeyChange,
  regressionThreshold,
  onThresholdChange,
  updateBaseline,
  onUpdateBaselineChange,
}: {
  result: EvalRunResponse | null;
  baselineKey: string;
  onBaselineKeyChange: (value: string) => void;
  regressionThreshold: number;
  onThresholdChange: (value: number) => void;
  updateBaseline: boolean;
  onUpdateBaselineChange: (value: boolean) => void;
}) {
  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="space-y-1 text-sm text-stone-600 dark:text-stone-300">
          <span>比较组</span>
          <input className={INPUT_CLASS} value={baselineKey} onChange={event => onBaselineKeyChange(event.target.value)} />
        </label>
        <label className="space-y-1 text-sm text-stone-600 dark:text-stone-300">
          <span>允许波动</span>
          <input
            type="number"
            step="0.01"
            className={INPUT_CLASS}
            value={regressionThreshold}
            onChange={event => onThresholdChange(parseFloat(event.target.value) || 0)}
          />
        </label>
      </div>
      <label className="flex items-center gap-2 text-sm text-stone-600 dark:text-stone-300">
        <input type="checkbox" checked={updateBaseline} onChange={event => onUpdateBaselineChange(event.target.checked)} />
        <span>把这次存成以后的比较基准</span>
      </label>
      {!result?.baselineComparison && (
        <p className="text-sm text-stone-500">
          还没有可对比的基准。先跑一次并勾选「保存为基准」，之后同一比较组的结果才会出现涨跌。
        </p>
      )}
      {result?.baselineComparison && (
        <div className="space-y-2">
          {result.baselineComparison.metrics.map(metric => (
            <div key={metric.metric} className="flex flex-wrap items-center gap-2 text-sm">
              <span className={metric.regressed ? 'text-rose-600' : 'text-emerald-600'}>{metric.regressed ? '退步' : '稳定'}</span>
              <span className="text-stone-700 dark:text-stone-200">{metricLabel(metric.metric)}</span>
              <span className="text-stone-400">
                当前 {num(metric.current)} · 基准 {num(metric.baseline)} · 差 {num(metric.delta)}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function EvalResult({
  result,
  diagnosis,
  reportText,
  copyHint,
  onCopy,
  onSelectGate,
}: {
  result: EvalRunResponse;
  diagnosis: { title: string; detail: string; tone: FlowTone };
  reportText: string;
  copyHint: string | null;
  onCopy: () => void;
  onSelectGate: (gate: EvalGateId) => void;
}) {
  const standard = buildStandardRagMetrics(result);
  return (
    <section className="space-y-4">
      <div className={`rounded-xl border px-4 py-3 ${toneBox(diagnosis.tone)}`}>
        <p className="text-sm font-semibold">{diagnosis.title}</p>
        <p className="mt-1 text-sm leading-6">{diagnosis.detail}</p>
      </div>

      <div className="surface-card space-y-5 p-5">
        <div className="flex flex-wrap items-center gap-3">
          <div>
            <p className="text-xs text-stone-400">综合分</p>
            <p className="text-3xl font-semibold tabular-nums text-primary-700 dark:text-primary-300">{pct(result.overallScore)}</p>
          </div>
          <span className={`rounded-full px-2.5 py-1 text-xs font-medium ${result.regression ? badgeClass('fail') : badgeClass('ok')}`}>
            {result.regression ? '低于历史基准' : '没有低于历史基准'}
          </span>
          {result.baseline && (
            <span className="rounded-full bg-sky-100 px-2.5 py-1 text-xs text-sky-700 dark:bg-sky-950/40 dark:text-sky-200">
              已存为基准
            </span>
          )}
          {result.intent && result.intent.total > 0 && (
            <span className="rounded-full bg-stone-100 px-2.5 py-1 text-xs text-stone-600 dark:bg-stone-800 dark:text-stone-300">
              意图门 {result.intent.correct}/{result.intent.total}
            </span>
          )}
          <button type="button" onClick={onCopy} className="btn-secondary ml-auto inline-flex items-center gap-2 px-3 py-1.5 text-sm">
            <ClipboardCopy className="h-4 w-4" />
            复制报告
          </button>
        </div>
        {copyHint && <p className="text-xs text-emerald-600 dark:text-emerald-400">{copyHint}</p>}

        <div>
          <p className="mb-2 text-xs font-medium text-stone-500">标准五指标</p>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
            {standard.map(metric => (
              <StandardMetricCard key={metric.id} metric={metric} onSelect={() => onSelectGate(metric.gate)} />
            ))}
          </div>
        </div>

        {result.rag && (
          <div className="flex flex-wrap gap-2 text-xs text-stone-500">
            <span className="rounded-full bg-stone-100 px-2.5 py-1 dark:bg-stone-800">Hit@{result.rag.k} {pct(result.rag.hitRate)}</span>
            <span className="rounded-full bg-stone-100 px-2.5 py-1 dark:bg-stone-800">MRR {num(result.rag.mrr)}</span>
            <span className="rounded-full bg-stone-100 px-2.5 py-1 dark:bg-stone-800">nDCG {num(result.rag.ndcg)}</span>
          </div>
        )}

        {result.qualityGate && Object.keys(result.qualityGate.metrics).length > 0 && (
          <div>
            <p className="mb-2 text-xs font-medium text-stone-500">质量门明细</p>
            <div className="flex flex-wrap gap-2">
              {Object.entries(result.qualityGate.metrics).map(([key, value]) => {
                const threshold = result.qualityGate.thresholds[key] ?? 0;
                const passed = value >= threshold;
                return (
                  <span key={key} className={`rounded-full px-2.5 py-1 text-xs ${passed ? badgeClass('ok') : badgeClass('fail')}`}>
                    {metricLabel(key)} {pct(value)} / {pct(threshold)}
                  </span>
                );
              })}
            </div>
          </div>
        )}

        <details className="rounded-xl border border-stone-200 dark:border-stone-800">
          <summary className="cursor-pointer px-3 py-2 text-sm text-stone-600 dark:text-stone-300">可复制的文字报告</summary>
          <pre className="max-h-64 overflow-auto whitespace-pre-wrap p-3 text-xs text-stone-700 dark:text-stone-200">
            {reportText}
          </pre>
        </details>
      </div>
    </section>
  );
}

function StandardMetricCard({
  metric,
  onSelect,
}: {
  metric: StandardRagMetricCard;
  onSelect: () => void;
}) {
  const ready = metric.value != null;
  return (
    <button
      type="button"
      onClick={onSelect}
      className="rounded-xl border border-stone-200 px-3 py-3 text-left transition hover:border-primary-300 dark:border-stone-800 dark:hover:border-primary-800"
    >
      <p className="text-[11px] font-medium text-stone-400">{metric.layerLabel}</p>
      <p className="mt-1 text-xs font-semibold text-stone-800 dark:text-stone-100">{metric.name}</p>
      <p className="mt-1 text-xl font-semibold tabular-nums text-stone-900 dark:text-white">
        {ready ? pct(metric.value as number) : '未跑'}
      </p>
      <p className="mt-1 text-[11px] leading-4 text-stone-400">{metric.zh} · {metric.asks}</p>
    </button>
  );
}
