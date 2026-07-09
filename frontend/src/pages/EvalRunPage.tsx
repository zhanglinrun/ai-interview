import { useEffect, useState } from 'react';
import { Play, Plus, Trash2 } from 'lucide-react';
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
} from '../api/eval';

const pct = (v: number) => `${(v * 100).toFixed(1)}%`;
const num = (v: number) => v.toFixed(3);

const INPUT_CLASS =
  'w-full px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 ' +
  'text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50';

export default function EvalRunPage() {
  const [title, setTitle] = useState('面试路由基础回归');
  const [baselineKey, setBaselineKey] = useState('interview-routing-basic');
  const [updateBaseline, setUpdateBaseline] = useState(false);
  const [regressionThreshold, setRegressionThreshold] = useState(0.03);

  const [intentCases, setIntentCases] = useState<IntentCase[]>([]);
  const [judgeCases, setJudgeCases] = useState<JudgeCase[]>([]);
  const [ragKbIds, setRagKbIds] = useState<number[]>([]);
  const [ragK, setRagK] = useState(5);
  const [ragItems, setRagItems] = useState<RagEvalItem[]>([]);

  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<EvalRunResponse | null>(null);

  useEffect(() => {
    knowledgeBaseApi
      .getAllKnowledgeBases('time', 'VECTOR_STORED')
      .then(setKnowledgeBases)
      .catch(err => console.error('Failed to load knowledge bases:', err));
  }, []);

  const loadExample = () => {
    setTitle('面试路由基础回归');
    setBaselineKey('interview-routing-basic');
    setUpdateBaseline(false);
    setRegressionThreshold(0.03);
    setIntentCases([
      { question: '讲讲 JVM 垃圾回收原理', expectedIntent: 'TECH_KB' },
      { question: '今天天气怎么样', expectedIntent: 'OFF_TOPIC' },
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
    ]);
  };

  const run = async () => {
    setError(null);
    setResult(null);
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
      setError('请至少填写一类评测用例（意图识别 / RAG 检索 / LLM-as-Judge）。');
      return;
    }
    setRunning(true);
    try {
      setResult(await evalApi.run(body));
    } catch (err) {
      setError(getErrorMessage(err, '评测运行失败'));
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="max-w-5xl mx-auto px-4 py-6 space-y-6">
      <PageHeader
        eyebrow="评测"
        title="统一评测闭环"
        description="意图识别 + RAG 检索 + LLM-as-Judge 回答质量 + 基线回归，一次运行留痕可追踪"
      />

      <div className="flex flex-wrap gap-3">
        <button onClick={loadExample} className="px-4 py-2 rounded-lg btn-secondary text-sm font-medium">
          加载示例
        </button>
        <button
          onClick={run}
          disabled={running}
          className="px-5 py-2 rounded-lg btn-primary text-sm font-medium inline-flex items-center gap-2 disabled:opacity-50"
        >
          <Play className="w-4 h-4" />
          {running ? '评测运行中…' : '运行评测'}
        </button>
      </div>

      {error && (
        <div className="rounded-lg border border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-900/20 px-4 py-3 text-sm text-red-700 dark:text-red-300">
          {error}
        </div>
      )}

      {/* 运行配置 */}
      <section className="rounded-xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
        <h2 className="font-semibold text-sm text-slate-900 dark:text-white">运行配置</h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <label className="text-sm text-slate-600 dark:text-slate-300 space-y-1">
            <span>标题</span>
            <input className={INPUT_CLASS} value={title} onChange={e => setTitle(e.target.value)} />
          </label>
          <label className="text-sm text-slate-600 dark:text-slate-300 space-y-1">
            <span>基线 Key（baselineKey）</span>
            <input className={INPUT_CLASS} value={baselineKey} onChange={e => setBaselineKey(e.target.value)} />
          </label>
          <label className="text-sm text-slate-600 dark:text-slate-300 space-y-1">
            <span>回归阈值（regressionThreshold）</span>
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
            <span>将本次结果设为新基线（updateBaseline）</span>
          </label>
        </div>
      </section>

      {/* 意图识别用例 */}
      <section className="rounded-xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="font-semibold text-sm text-slate-900 dark:text-white">意图识别用例</h2>
          <button
            onClick={() => setIntentCases(prev => [...prev, { question: '', expectedIntent: '' }])}
            className="text-primary-600 dark:text-primary-400 text-sm inline-flex items-center gap-1"
          >
            <Plus className="w-4 h-4" /> 添加
          </button>
        </div>
        {intentCases.length === 0 && <p className="text-sm text-slate-400">未添加意图用例（可选）</p>}
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
              placeholder="期望意图，如 TECH_KB"
              value={c.expectedIntent ?? ''}
              onChange={e => setIntentCases(prev => prev.map((x, j) => (j === i ? { ...x, expectedIntent: e.target.value } : x)))}
            />
            <button onClick={() => setIntentCases(prev => prev.filter((_, j) => j !== i))} className="text-slate-400 hover:text-red-500">
              <Trash2 className="w-4 h-4" />
            </button>
          </div>
        ))}
      </section>

      {/* RAG 检索用例 */}
      <section className="rounded-xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="font-semibold text-sm text-slate-900 dark:text-white">RAG 检索用例</h2>
          <button
            onClick={() => setRagItems(prev => [...prev, { question: '', expectedKeywords: [] }])}
            className="text-primary-600 dark:text-primary-400 text-sm inline-flex items-center gap-1"
          >
            <Plus className="w-4 h-4" /> 添加
          </button>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <label className="text-sm text-slate-600 dark:text-slate-300 space-y-1">
            <span>知识库（可多选）</span>
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
            <span>Top-K</span>
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
          <p className="text-xs text-amber-600 dark:text-amber-400">已填 RAG 用例但未选知识库，本次将跳过 RAG 评测。</p>
        )}
      </section>

      {/* LLM-as-Judge 用例 */}
      <section className="rounded-xl border border-slate-200 dark:border-slate-700 p-4 space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="font-semibold text-sm text-slate-900 dark:text-white">回答质量用例（LLM-as-Judge）</h2>
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

      {result && <EvalResult result={result} />}
    </div>
  );
}

function EvalResult({ result }: { result: EvalRunResponse }) {
  return (
    <section className="rounded-xl border border-slate-200 dark:border-slate-700 p-4 space-y-5">
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
          {result.regression ? '相对基线退化' : '未退化'}
        </span>
        {result.baseline && (
          <span className="px-2.5 py-1 rounded-full text-xs bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
            已记为新基线
          </span>
        )}
        <span className="text-xs text-slate-400">runId: {result.runId}</span>
      </div>

      {result.intent && (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">意图识别</h3>
          <p className="text-sm text-slate-500">
            准确率 {pct(result.intent.accuracy)} · Macro-F1 {num(result.intent.macroF1)} · {result.intent.correct}/{result.intent.total} 正确
          </p>
          <div className="space-y-1">
            {result.intent.items.map((it, i) => (
              <div key={i} className="text-xs flex flex-wrap gap-2 items-center">
                <span className={it.correct ? 'text-emerald-500' : 'text-red-500'}>{it.correct ? '✓' : '✗'}</span>
                <span className="text-slate-600 dark:text-slate-300">{it.question}</span>
                <span className="text-slate-400">期望={it.expectedIntent ?? '-'} 实际={it.actualIntent}（{pct(it.confidence)}）</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {result.rag && (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">RAG 检索</h3>
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
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">回答质量（LLM-as-Judge）</h3>
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
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">基线对比</h3>
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
    </section>
  );
}
