import { useEffect, useState } from 'react';
import { ClipboardCopy, Play, Plus, Trash2 } from 'lucide-react';
import PageHeader from '../components/ui/PageHeader';
import { getErrorMessage } from '../api/request';
import { knowledgeBaseApi, type KnowledgeBaseItem } from '../api/knowledgebase';
import {
  evalApi,
  type EvalRunRequest,
  type EvalRunResponse,
  type IntentCase,
  type JudgeCase,
  type RagEvalItem,
  type EvalRunSummary,
} from '../api/eval';

const pct = (v: number) => `${(v * 100).toFixed(1)}%`;
const num = (v: number) => v.toFixed(3);

const INPUT_CLASS = 'dark-input w-full px-3 py-2 text-sm';

function formatEvalReport(result: EvalRunResponse): string {
  const lines: string[] = [
    `# ${result.title}`,
    `runId: ${result.runId}`,
    `时间: ${result.createdAt}`,
    `总分: ${pct(result.overallScore)}${result.regression ? '（低于历史基准）' : ''}`,
    '',
  ];
  if (result.intent) {
    lines.push(
      '## 意图门 / 问题分类',
      `准确率 ${pct(result.intent.accuracy)} · Macro-F1 ${num(result.intent.macroF1)} · ${result.intent.correct}/${result.intent.total}`,
    );
    for (const it of result.intent.items) {
      lines.push(
        `- [${it.correct ? 'OK' : 'FAIL'}] ${it.question} → 期望=${it.expectedIntent ?? '-'} 实际=${it.actualIntent} (${pct(it.confidence)})`,
      );
    }
    lines.push('');
  }
  if (result.rag) {
    lines.push(
      '## 资料检索',
      `Hit ${pct(result.rag.hitRate)} · MRR ${num(result.rag.mrr)} · NDCG ${num(result.rag.ndcg)} · Top-${result.rag.k}`,
    );
    for (const it of result.rag.items) {
      lines.push(
        `- [${it.hit ? 'HIT' : 'MISS'}] ${it.question} · firstRank=${it.firstHitRank} · RR=${num(it.reciprocalRank)}`,
      );
    }
    lines.push('');
  }
  if (result.judge) {
    lines.push(
      '## 回答质量',
      `通过率 ${pct(result.judge.passRate)} · 平均分 ${num(result.judge.averageOverall)} · ${result.judge.passed}/${result.judge.total}`,
    );
    for (const it of result.judge.items) {
      lines.push(
        `- [${it.passed ? 'PASS' : 'FAIL'}] ${it.question} · overall=${num(it.overall)} (门槛 ${num(it.minOverallScore)})`,
      );
    }
    lines.push('');
  }
  if (result.baselineComparison) {
    lines.push(
      '## 基线对比',
      `baseline=${result.baselineComparison.baselineRunId} · 阈值=${num(result.baselineComparison.threshold)}`,
    );
    for (const m of result.baselineComparison.metrics) {
      lines.push(
        `- ${m.metric}: 当前 ${num(m.current)} vs 基线 ${num(m.baseline)} (Δ ${num(m.delta)})${m.regressed ? ' [回归]' : ''}`,
      );
    }
  }
  lines.push(
    '',
    '复现：侧栏「RAG 评测」→ 默认 Agent Demo → 选择 VECTOR_STORED 资料 → 运行评测。',
    'Agent Critic 质量门：数据集 eval/interview-agent/critic-badcase-dataset.yaml；本地报告 eval/.work/critic-badcase-report.md（小样本）。',
    '更深报告：eval/rag-retrieval、eval/ragas（见 eval/README.md）。',
  );
  return lines.join('\n');
}

export default function EvalRunPage() {
  const [title, setTitle] = useState('Agent Demo');
  const [baselineKey, setBaselineKey] = useState('agent-demo-baseline');
  const [updateBaseline, setUpdateBaseline] = useState(false);
  const [regressionThreshold, setRegressionThreshold] = useState(0.03);

  const [intentCases, setIntentCases] = useState<IntentCase[]>([
    { question: '讲讲 JVM 垃圾回收原理', expectedIntent: 'TECH_KB', expectedRelated: true },
    { question: 'Redis 缓存穿透怎么解决？', expectedIntent: 'TECH_KB', expectedRelated: true },
    { question: '今天天气怎么样', expectedIntent: 'OFF_TOPIC', expectedRelated: false },
    { question: '帮我点一份外卖', expectedIntent: 'OFF_TOPIC', expectedRelated: false },
  ]);
  const [judgeCases, setJudgeCases] = useState<JudgeCase[]>([
    {
      question: 'Redis 缓存穿透怎么解决？',
      answer: '可以用布隆过滤器拦截不存在的 key，并对不存在的数据做短 TTL 空值缓存。',
      referenceAnswer: '布隆过滤器、参数校验、空值缓存、热点保护。',
      context: '缓存穿透指查询不存在的数据导致请求打到数据库。',
      minOverallScore: 0.75,
    },
  ]);
  const [ragKbIds, setRagKbIds] = useState<number[]>([]);
  const [ragK, setRagK] = useState(5);
  const [ragItems, setRagItems] = useState<RagEvalItem[]>([
    { question: 'Redis 缓存穿透怎么解决？', expectedKeywords: ['布隆过滤器', '空值缓存'] },
    { question: 'JVM 垃圾回收有哪些常见收集器？', expectedKeywords: ['G1', '垃圾回收'] },
  ]);

  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<EvalRunResponse | null>(null);
  const [copyHint, setCopyHint] = useState<string | null>(null);
  const [recentRuns, setRecentRuns] = useState<EvalRunSummary[]>([]);

  const loadExample = () => {
    setTitle('Agent Demo');
    setBaselineKey('agent-demo-baseline');
    setUpdateBaseline(false);
    setRegressionThreshold(0.03);
    setIntentCases([
      { question: '讲讲 JVM 垃圾回收原理', expectedIntent: 'TECH_KB', expectedRelated: true },
      { question: 'Redis 缓存穿透怎么解决？', expectedIntent: 'TECH_KB', expectedRelated: true },
      { question: '今天天气怎么样', expectedIntent: 'OFF_TOPIC', expectedRelated: false },
      { question: '帮我点一份外卖', expectedIntent: 'OFF_TOPIC', expectedRelated: false },
    ]);
    setJudgeCases([
      {
        question: 'Redis 缓存穿透怎么解决？',
        answer: '可以用布隆过滤器拦截不存在的 key，并对不存在的数据做短 TTL 空值缓存。',
        referenceAnswer: '布隆过滤器、参数校验、空值缓存、热点保护。',
        context: '缓存穿透指查询不存在的数据导致请求打到数据库。',
        minOverallScore: 0.75,
      },
    ]);
    setRagItems([
      { question: 'Redis 缓存穿透怎么解决？', expectedKeywords: ['布隆过滤器', '空值缓存'] },
      { question: 'JVM 垃圾回收有哪些常见收集器？', expectedKeywords: ['G1', '垃圾回收'] },
    ]);
    if (knowledgeBases.length > 0) {
      setRagKbIds([knowledgeBases[0].id]);
    }
  };

  useEffect(() => {
    knowledgeBaseApi
      .getAllKnowledgeBases('time', 'VECTOR_STORED')
      .then((list) => {
        setKnowledgeBases(list);
        if (list.length > 0) {
          setRagKbIds((prev) => (prev.length === 0 ? [list[0].id] : prev));
        }
      })
      .catch(err => console.error('Failed to load knowledge bases:', err));
  }, []);

  useEffect(() => {
    evalApi.list(20).then(setRecentRuns).catch(() => setRecentRuns([]));
  }, []);

  const run = async () => {
    setError(null);
    setResult(null);
    setCopyHint(null);
    const body: EvalRunRequest = {
      title: title.trim() || undefined,
      baselineKey: baselineKey.trim() || undefined,
      updateBaseline,
      regressionThreshold,
    };
    if (intentCases.length > 0) {
      body.intentCases = intentCases.filter(c => c.question.trim());
    }
    if (judgeCases.length > 0) {
      body.judgeCases = judgeCases.filter(c => c.question.trim() && c.answer.trim());
    }
    if (ragItems.length > 0 && ragKbIds.length > 0) {
      body.rag = {
        knowledgeBaseIds: ragKbIds,
        k: ragK,
        items: ragItems.filter(i => i.question.trim()),
      };
    }
    if (!body.intentCases?.length && !body.judgeCases?.length && !body.rag) {
      setError('请至少添加一组评测用例。');
      return;
    }
    setRunning(true);
    try {
      const next = await evalApi.run(body);
      setResult(next);
      setRecentRuns(await evalApi.list(20).catch(() => recentRuns));
    } catch (err) {
      setError(getErrorMessage(err, '评测运行失败'));
    } finally {
      setRunning(false);
    }
  };

  const copyReport = async () => {
    if (!result) return;
    try {
      await navigator.clipboard.writeText(formatEvalReport(result));
      setCopyHint('报告已复制到剪贴板，可直接贴进答辩笔记。');
    } catch {
      setCopyHint('复制失败，请手动选中下方报告文本。');
    }
  };

  return (
    <div className="max-w-5xl mx-auto space-y-5">
      <PageHeader
        eyebrow="Agent Demo"
        title="RAG 效果评测"
        description="默认载入固定集；一键检查意图 Macro-F1、检索 Hit/MRR/NDCG、Judge 与基线对比。Critic bad-case 见 eval/.work 作为 Agent 质量门备注。"
      />

      <div className="rounded-lg border border-primary-100 bg-primary-50/60 px-4 py-3 text-sm text-primary-900 dark:border-primary-900 dark:bg-primary-950/30 dark:text-primary-200">
        建议：确认已选 VECTOR_STORED 资料后直接「运行评测」。Agent Critic 固定集在
        {' '}<code className="text-xs">eval/interview-agent/critic-badcase-dataset.yaml</code>
        ，本地跑 <code className="text-xs">InterviewCriticEvalTest</code> 后报告落
        {' '}<code className="text-xs">eval/.work/critic-badcase-report.md</code>（小样本质量门，非端到端成功率）。
        离线四档 / RAGAS 见 <code className="text-xs">eval/rag-retrieval</code> 与 <code className="text-xs">eval/ragas</code>。
      </div>

      <div className="flex flex-wrap gap-3">
        <button onClick={loadExample} className="px-4 py-2 rounded-lg btn-secondary text-sm font-medium">
          加载 Agent Demo
        </button>
        <button
          onClick={run}
          disabled={running}
          className="px-5 py-2 rounded-lg btn-primary text-sm font-medium inline-flex items-center gap-2 disabled:opacity-50"
        >
          <Play className="w-4 h-4" />
          {running ? '评测运行中…' : '运行评测'}
        </button>
        {result && (
          <button
            onClick={() => void copyReport()}
            className="px-4 py-2 rounded-lg btn-secondary text-sm font-medium inline-flex items-center gap-2"
          >
            <ClipboardCopy className="w-4 h-4" />
            复制报告
          </button>
        )}
      </div>
      {copyHint && (
        <p className="text-xs text-emerald-600 dark:text-emerald-400">{copyHint}</p>
      )}

      {error && (
        <div className="rounded-lg border border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-900/20 px-4 py-3 text-sm text-red-700 dark:text-red-300">
          {error}
        </div>
      )}

      <section className="surface-card p-4 space-y-3">
        <h2 className="font-semibold text-sm text-slate-900 dark:text-white">运行配置</h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <label className="text-sm text-slate-600 dark:text-slate-300 space-y-1">
            <span>运行名称</span>
            <input className={INPUT_CLASS} value={title} onChange={e => setTitle(e.target.value)} />
          </label>
          <label className="text-sm text-slate-600 dark:text-slate-300 space-y-1">
            <span>基线标识</span>
            <input className={INPUT_CLASS} value={baselineKey} onChange={e => setBaselineKey(e.target.value)} />
          </label>
          <label className="text-sm text-slate-600 dark:text-slate-300 space-y-1">
            <span>允许波动</span>
            <input
              type="number"
              step="0.01"
              className={INPUT_CLASS}
              value={regressionThreshold}
              onChange={e => setRegressionThreshold(parseFloat(e.target.value) || 0)}
            />
          </label>
          <label className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300 self-end pb-2">
            <input type="checkbox" checked={updateBaseline} onChange={e => setUpdateBaseline(e.target.checked)} />
            <span>将本次结果保存为后续比较基准</span>
          </label>
        </div>
      </section>

      {recentRuns.length > 0 && (
        <section className="surface-card p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="font-semibold text-sm text-slate-900 dark:text-white">最近评测与质量门</h2>
            <span className="text-xs text-slate-400">门槛来自后端配置</span>
          </div>
          <div className="grid gap-2 md:grid-cols-2">
            {recentRuns.slice(0, 6).map(run => (
              <button key={run.runId} type="button" onClick={() => void evalApi.get(run.runId).then(setResult).catch(() => undefined)} className="rounded-lg border border-slate-100 p-3 text-left transition hover:border-primary-200 dark:border-slate-800 dark:hover:border-primary-800">
                <div className="flex items-center justify-between gap-2"><span className="truncate text-sm text-slate-700 dark:text-slate-200">{run.title}</span><span className={`rounded-full px-2 py-0.5 text-xs ${run.qualityGate?.passed ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/30 dark:text-emerald-300' : 'bg-amber-50 text-amber-700 dark:bg-amber-950/30 dark:text-amber-300'}`}>{run.qualityGate?.passed ? 'PASS' : '需关注'}</span></div>
                <p className="mt-1 text-xs text-slate-400">{run.runId.slice(0, 16)} · 总分 {pct(run.overallScore)} · {run.createdAt}</p>
                {(run.qualityGate?.failures?.length ?? 0) > 0 && <p className="mt-1 line-clamp-1 text-xs text-amber-600">{run.qualityGate.failures.join('；')}</p>}
              </button>
            ))}
          </div>
        </section>
      )}

      <section className="surface-card p-4 space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="font-semibold text-sm text-slate-900 dark:text-white">意图门 / 问题分类用例</h2>
          <button
            onClick={() => setIntentCases(prev => [...prev, { question: '', expectedIntent: '' }])}
            className="text-primary-600 dark:text-primary-400 text-sm inline-flex items-center gap-1"
          >
            <Plus className="w-4 h-4" /> 添加
          </button>
        </div>
        {intentCases.length === 0 && <p className="text-sm text-slate-400">暂未添加，可选</p>}
        {intentCases.map((c, i) => (
          <div key={i} className="flex flex-wrap gap-2 items-center">
            <input
              className={`${INPUT_CLASS} flex-1 min-w-[240px]`}
              placeholder="问题，如：讲讲 JVM 垃圾回收原理"
              value={c.question}
              onChange={e => setIntentCases(prev => prev.map((x, j) => (j === i ? { ...x, question: e.target.value } : x)))}
            />
            <input
              className={`${INPUT_CLASS} w-40`}
              placeholder="期望类型，如 TECH_KB"
              value={c.expectedIntent ?? ''}
              onChange={e => setIntentCases(prev => prev.map((x, j) => (j === i ? { ...x, expectedIntent: e.target.value } : x)))}
            />
            <button onClick={() => setIntentCases(prev => prev.filter((_, j) => j !== i))} className="text-slate-400 hover:text-red-500">
              <Trash2 className="w-4 h-4" />
            </button>
          </div>
        ))}
      </section>

      <section className="surface-card p-4 space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="font-semibold text-sm text-slate-900 dark:text-white">资料检索用例</h2>
          <button
            onClick={() => setRagItems(prev => [...prev, { question: '', expectedKeywords: [] }])}
            className="text-primary-600 dark:text-primary-400 text-sm inline-flex items-center gap-1"
          >
            <Plus className="w-4 h-4" /> 添加
          </button>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <label className="text-sm text-slate-600 dark:text-slate-300 space-y-1">
            <span>检索资料（可多选）</span>
            <select
              multiple
              className={`${INPUT_CLASS} h-24`}
              value={ragKbIds.map(String)}
              onChange={e => setRagKbIds(Array.from(e.target.selectedOptions, o => Number(o.value)))}
            >
              {knowledgeBases.map(kb => (
                <option key={kb.id} value={kb.id}>{kb.name}</option>
              ))}
            </select>
          </label>
          <label className="text-sm text-slate-600 dark:text-slate-300 space-y-1">
            <span>返回条数</span>
            <input type="number" min={1} className={INPUT_CLASS} value={ragK} onChange={e => setRagK(parseInt(e.target.value) || 5)} />
          </label>
        </div>
        {ragItems.map((it, i) => (
          <div key={i} className="flex flex-wrap gap-2 items-center">
            <input
              className={`${INPUT_CLASS} flex-1 min-w-[240px]`}
              placeholder="问题，如：Redis 缓存穿透怎么解决？"
              value={it.question}
              onChange={e => setRagItems(prev => prev.map((x, j) => (j === i ? { ...x, question: e.target.value } : x)))}
            />
            <input
              className={`${INPUT_CLASS} flex-1 min-w-[200px]`}
              placeholder="期望关键点，逗号分隔"
              value={(it.expectedKeywords ?? []).join(',')}
              onChange={e =>
                setRagItems(prev =>
                  prev.map((x, j) =>
                    j === i ? { ...x, expectedKeywords: e.target.value.split(',').map(s => s.trim()).filter(Boolean) } : x
                  )
                )
              }
            />
            <button onClick={() => setRagItems(prev => prev.filter((_, j) => j !== i))} className="text-slate-400 hover:text-red-500">
              <Trash2 className="w-4 h-4" />
            </button>
          </div>
        ))}
        {ragItems.length > 0 && ragKbIds.length === 0 && (
          <p className="text-xs text-amber-600 dark:text-amber-400">尚未选择资料，这组检索用例不会运行。</p>
        )}
      </section>

      <section className="surface-card p-4 space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="font-semibold text-sm text-slate-900 dark:text-white">回答质量用例</h2>
          <button
            onClick={() => setJudgeCases(prev => [...prev, { question: '', answer: '', minOverallScore: 0.75 }])}
            className="text-primary-600 dark:text-primary-400 text-sm inline-flex items-center gap-1"
          >
            <Plus className="w-4 h-4" /> 添加
          </button>
        </div>
        {judgeCases.length === 0 && <p className="text-sm text-slate-400">未添加回答质量用例（可选）</p>}
        {judgeCases.map((c, i) => (
          <div key={i} className="rounded-lg border border-slate-100 dark:border-slate-800 p-3 space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs text-slate-400">用例 {i + 1}</span>
              <button onClick={() => setJudgeCases(prev => prev.filter((_, j) => j !== i))} className="text-slate-400 hover:text-red-500">
                <Trash2 className="w-4 h-4" />
              </button>
            </div>
            <input
              className={INPUT_CLASS}
              placeholder="问题"
              value={c.question}
              onChange={e => setJudgeCases(prev => prev.map((x, j) => (j === i ? { ...x, question: e.target.value } : x)))}
            />
            <textarea
              className={`${INPUT_CLASS} h-16 resize-none`}
              placeholder="待评估回答"
              value={c.answer}
              onChange={e => setJudgeCases(prev => prev.map((x, j) => (j === i ? { ...x, answer: e.target.value } : x)))}
            />
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <input
                className={INPUT_CLASS}
                placeholder="参考答案（可选）"
                value={c.referenceAnswer ?? ''}
                onChange={e => setJudgeCases(prev => prev.map((x, j) => (j === i ? { ...x, referenceAnswer: e.target.value } : x)))}
              />
              <input
                type="number"
                step="0.05"
                className={INPUT_CLASS}
                placeholder="最低总分阈值，如 0.75"
                value={c.minOverallScore ?? ''}
                onChange={e => setJudgeCases(prev => prev.map((x, j) => (j === i ? { ...x, minOverallScore: parseFloat(e.target.value) || undefined } : x)))}
              />
            </div>
          </div>
        ))}
      </section>

      {result && <EvalResult result={result} reportText={formatEvalReport(result)} />}
    </div>
  );
}

function EvalResult({ result, reportText }: { result: EvalRunResponse; reportText: string }) {
  return (
    <section className="surface-card p-4 space-y-5">
      <div className="flex flex-wrap items-center gap-4">
        <h2 className="font-semibold text-slate-900 dark:text-white">评测结果</h2>
        <span className="text-2xl font-bold text-primary-600 dark:text-primary-400 tabular-nums">
          {pct(result.overallScore)}
        </span>
        <span
          className={`px-2.5 py-1 rounded-full text-xs font-medium ${
            result.regression
              ? 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
              : 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300'
          }`}
        >
          {result.regression ? '低于历史基准' : '未低于历史基准'}
        </span>
        {result.baseline && (
          <span className="px-2.5 py-1 rounded-full text-xs bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
            已保存为基准
          </span>
        )}
        <span className="text-xs text-slate-400">运行编号：{result.runId}</span>
      </div>

      {result.qualityGate && (
        <div className={`rounded-lg border px-3 py-3 ${result.qualityGate.passed ? 'border-emerald-200 bg-emerald-50/70 dark:border-emerald-900 dark:bg-emerald-950/20' : 'border-amber-200 bg-amber-50/70 dark:border-amber-900 dark:bg-amber-950/20'}`}>
          <div className="flex flex-wrap items-center gap-2"><h3 className="text-sm font-semibold text-slate-800 dark:text-slate-100">质量门：{result.qualityGate.passed ? '通过' : '未通过'}</h3><span className="text-xs text-slate-500">意图、检索、引用、groundedness 与回答质量阈值</span></div>
          <div className="mt-2 flex flex-wrap gap-2">{Object.entries(result.qualityGate.metrics).map(([key, value]) => <span key={key} className={`rounded-full px-2 py-1 text-xs ${value >= (result.qualityGate.thresholds[key] ?? 0) ? 'bg-white/80 text-emerald-700 dark:bg-slate-900/70 dark:text-emerald-300' : 'bg-white/80 text-amber-700 dark:bg-slate-900/70 dark:text-amber-300'}`}>{key}: {pct(value)} / {pct(result.qualityGate.thresholds[key] ?? 0)}</span>)}</div>
          {result.qualityGate.failures.length > 0 && <p className="mt-2 text-xs text-amber-700 dark:text-amber-300">失败项：{result.qualityGate.failures.join('；')}</p>}
        </div>
      )}

      {result.intent && (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">意图门 / 问题分类</h3>
          <p className="text-sm text-slate-500">
            准确率 {pct(result.intent.accuracy)} · Macro-F1 {num(result.intent.macroF1)} · {result.intent.correct}/{result.intent.total} 正确
          </p>
          <div className="space-y-1">
            {result.intent.items.map((it, i) => (
              <div key={i} className="text-xs flex flex-wrap gap-2 items-center">
                <span className={it.correct ? 'text-emerald-500' : 'text-red-500'}>{it.correct ? '✓' : '✗'}</span>
                <span className="text-slate-600 dark:text-slate-300">{it.question}</span>
                <span className="text-slate-400">期望={it.expectedIntent ?? '-'} 实际={it.actualIntent}（{pct(it.confidence)}）</span>
                {it.actualRelated != null && (
                  <span className="text-slate-400">related={String(it.actualRelated)}</span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {result.rag && (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">资料检索</h3>
          <p className="text-sm text-slate-500">
            命中率 {pct(result.rag.hitRate)} · MRR {num(result.rag.mrr)} · NDCG {num(result.rag.ndcg)} · Top-{result.rag.k} · {result.rag.total} 题
          </p>
          <div className="space-y-1">
            {result.rag.items.map((it, i) => (
              <div key={i} className="text-xs flex flex-wrap gap-2 items-center">
                <span className={it.hit ? 'text-emerald-500' : 'text-red-500'}>{it.hit ? '✓' : '✗'}</span>
                <span className="text-slate-600 dark:text-slate-300">{it.question}</span>
                <span className="text-slate-400">首命中 rank={it.firstHitRank} · RR={num(it.reciprocalRank)}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {result.judge && (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">回答质量</h3>
          <p className="text-sm text-slate-500">
            通过率 {pct(result.judge.passRate)} · 平均分 {num(result.judge.averageOverall)} · {result.judge.passed}/{result.judge.total} 通过
          </p>
          <div className="space-y-1">
            {result.judge.items.map((it, i) => (
              <div key={i} className="text-xs flex flex-wrap gap-2 items-center">
                <span className={it.passed ? 'text-emerald-500' : 'text-red-500'}>{it.passed ? '✓' : '✗'}</span>
                <span className="text-slate-600 dark:text-slate-300">{it.question}</span>
                <span className="text-slate-400">总分={num(it.overall)}（门槛 {num(it.minOverallScore)}）</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {result.baselineComparison && (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">历史基准对比</h3>
          <p className="text-xs text-slate-400">
            基线 runId {result.baselineComparison.baselineRunId} · 阈值 {num(result.baselineComparison.threshold)}
          </p>
          <div className="space-y-1">
            {result.baselineComparison.metrics.map((m, i) => (
              <div key={i} className="text-xs flex flex-wrap gap-2 items-center">
                <span className={m.regressed ? 'text-red-500' : 'text-emerald-500'}>{m.regressed ? '↓' : '·'}</span>
                <span className="text-slate-600 dark:text-slate-300 w-40">{m.metric}</span>
                <span className="text-slate-400">
                  当前 {num(m.current)} vs 基线 {num(m.baseline)}（Δ {num(m.delta)}）
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">可复制报告</h3>
        <pre className="max-h-64 overflow-auto rounded-lg bg-slate-50 p-3 text-xs text-slate-700 dark:bg-slate-900 dark:text-slate-200 whitespace-pre-wrap">
          {reportText}
        </pre>
      </div>
    </section>
  );
}
