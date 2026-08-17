import type { EvalRunResponse, QualityGate } from '../api/eval';
import type { FlowTone } from './ragTraceFlow';

export type EvalGateId = 'intent' | 'retrieve' | 'judge' | 'baseline';
export type EvalRunScope = 'intent' | 'retrieve' | 'judge' | 'all';

export function layersForScope(scope: EvalRunScope): { intent: boolean; retrieve: boolean; judge: boolean } {
  if (scope === 'all') {
    return { intent: true, retrieve: true, judge: true };
  }
  return {
    intent: scope === 'intent',
    retrieve: scope === 'retrieve',
    judge: scope === 'judge',
  };
}

export function runScopeLabel(scope: EvalRunScope): string {
  if (scope === 'intent') return '只评意图门';
  if (scope === 'retrieve') return '只评检索';
  if (scope === 'judge') return '只评生成';
  return '三层一起评';
}

export interface EvalKbCandidate {
  id: number;
  name: string;
  originalFilename?: string | null;
  category?: string | null;
}

function kbHaystack(kb: EvalKbCandidate): string {
  return `${kb.name} ${kb.originalFilename ?? ''} ${kb.category ?? ''}`;
}

function pickOneKb(items: EvalKbCandidate[]): EvalKbCandidate | undefined {
  if (items.length === 0) {
    return undefined;
  }
  const preferred = items.filter(kb => /面渣|八股/.test(kbHaystack(kb)));
  const pool = preferred.length > 0 ? preferred : items;
  return [...pool].sort((left, right) => right.id - left.id)[0];
}

/** 八股默认题对着 Redis / JVM 面渣库，不要默认勾简历。 */
export function pickDefaultEvalKbIds(list: EvalKbCandidate[]): number[] {
  const redis = list.filter(kb => /redis/i.test(kbHaystack(kb)));
  const jvm = list.filter(kb => /jvm/i.test(kbHaystack(kb)));
  const ids = [pickOneKb(redis), pickOneKb(jvm)]
    .filter((kb): kb is EvalKbCandidate => kb != null)
    .map(kb => kb.id);
  if (ids.length > 0) {
    return ids;
  }
  const faq = list.filter(kb => /面渣|八股/.test(kbHaystack(kb)));
  if (faq.length > 0) {
    return [faq[0].id];
  }
  return [];
}

export interface EvalGateDef {
  id: EvalGateId;
  order: number;
  title: string;
  summary: string;
  purpose: string;
  detects: string;
  how: string;
}

export const EVAL_GATES: EvalGateDef[] = [
  {
    id: 'intent',
    order: 1,
    title: '问题 q',
    summary: '该不该进入检索',
    purpose: '标准 RAG 从问题开始。本产品多一道门：八股、算法、项目提问进入检索；闲聊不进知识库。',
    detects: '会不会把「JVM / 项目缓存怎么设计」当成该查资料，把「今天天气」当成闲聊。',
    how: '对照线上 related 硬门。这是进 RAG 之前的产品门，RAGAS 五指标不覆盖这一项。',
  },
  {
    id: 'retrieve',
    order: 2,
    title: '检索上下文 c(q)',
    summary: 'Context Precision / Recall',
    purpose: '标准检索层：该找到的关键点覆盖了多少（Recall），找回来的有多少有用、有用的排得靠不靠前（Precision）。',
    detects: '问「缓存穿透」时，第 1 条应是「数据不存在打到库」，而不是「缓存击穿」。',
    how: '本页用关键词是否出现在检索片段里近似 Context Recall / Precision，并附带 Hit@K、MRR、nDCG。同一关键点的别名用 | 连接，命中任一写法即可。',
  },
  {
    id: 'judge',
    order: 3,
    title: '生成答案 a',
    summary: 'Faithfulness / Relevancy / Correctness',
    purpose: '标准生成层：有没有脱离给定上下文编造（Faithfulness），有没有答在点上（Relevancy），和参考答案比说对了没（Correctness）。',
    detects: '回答是否贴着检索上下文和参考要点，而不是看起来流畅但在编。',
    how: '本页用阅卷模型的准确/相关/综合分近似这三项。评的是用例里的回答，不是离线黄金集里系统新生成的答案。',
  },
  {
    id: 'baseline',
    order: 4,
    title: '对照与回归',
    summary: '和上次基准比有没有退步',
    purpose: '改检索或提示词之后，用同一组例题对比五指标和综合分，避免「这次看起来还行，其实比上周差」。',
    detects: 'Context Precision / Recall、Faithfulness、Relevancy、Correctness 相对基准有没有明显下滑。',
    how: '同一比较组下，和最近一次勾选「保存为基准」的结果比。',
  },
];

export type StandardRagLayer = 'retrieval' | 'generation' | 'end_to_end';

export interface StandardRagMetricCard {
  id: 'context_precision' | 'context_recall' | 'faithfulness' | 'answer_relevancy' | 'answer_correctness';
  layer: StandardRagLayer;
  layerLabel: string;
  name: string;
  zh: string;
  asks: string;
  value: number | null;
  how: string;
  gate: EvalGateId;
}

export function buildStandardRagMetrics(result: EvalRunResponse | null): StandardRagMetricCard[] {
  const retrievalReady = Boolean(result?.rag && result.rag.total > 0);
  const judgeReady = Boolean(result?.judge && result.judge.total > 0);
  return [
    {
      id: 'context_precision',
      layer: 'retrieval',
      layerLabel: '检索',
      name: 'Context Precision',
      zh: '检索精确率',
      asks: '找回来的片段里，有多少真的有用？',
      value: retrievalReady ? result!.rag!.retrievalPrecision : null,
      how: '本页用关键词命中的精确率近似。',
      gate: 'retrieve',
    },
    {
      id: 'context_recall',
      layer: 'retrieval',
      layerLabel: '检索',
      name: 'Context Recall',
      zh: '检索召回率',
      asks: '该找到的关键点，检索结果覆盖了多少？',
      value: retrievalReady ? result!.rag!.retrievalRecall : null,
      how: '本页用期望关键词是否出现在片段里近似。',
      gate: 'retrieve',
    },
    {
      id: 'faithfulness',
      layer: 'generation',
      layerLabel: '生成',
      name: 'Faithfulness',
      zh: '忠实度',
      asks: '答案里的说法能不能从给定上下文推出来？',
      value: judgeReady ? result!.judge!.averageAccuracy : null,
      how: '本页用阅卷「是否说对 / 是否贴着上下文」近似。',
      gate: 'judge',
    },
    {
      id: 'answer_relevancy',
      layer: 'generation',
      layerLabel: '生成',
      name: 'Answer Relevancy',
      zh: '答案相关性',
      asks: '回答有没有真正对着问题说，而不是答成别的？',
      value: judgeReady ? result!.judge!.averageRelevance : null,
      how: '本页用阅卷 relevance 近似。',
      gate: 'judge',
    },
    {
      id: 'answer_correctness',
      layer: 'end_to_end',
      layerLabel: '端到端',
      name: 'Answer Correctness',
      zh: '答案正确性',
      asks: '和参考答案比，说对了、说全了吗？',
      value: judgeReady ? result!.judge!.averageOverall : null,
      how: '本页用阅卷综合分近似。',
      gate: 'judge',
    },
  ];
}

export type IntentGateValue = 'RELATED' | 'OFF_TOPIC';

export const INTENT_OPTIONS: { value: IntentGateValue; label: string }[] = [
  { value: 'RELATED', label: '查资料（八股、算法、项目提问）' },
  { value: 'OFF_TOPIC', label: '闲聊（不该查资料）' },
];

const RELATED_INTENTS = new Set([
  'RELATED',
  'TECH_KB',
  'CODE_REVIEW',
  'RESUME_STATS',
  'INTERVIEW_PREP',
  'SCHEDULE',
  'CAREER',
]);

export function intentGateValue(expectedIntent?: string, expectedRelated?: boolean): IntentGateValue {
  if (expectedIntent === 'OFF_TOPIC' || expectedRelated === false) {
    return 'OFF_TOPIC';
  }
  return 'RELATED';
}

export function intentCaseFromGate(question: string, gate: IntentGateValue) {
  if (gate === 'OFF_TOPIC') {
    return { question, expectedIntent: 'OFF_TOPIC', expectedRelated: false };
  }
  return { question, expectedRelated: true };
}

/** 提交时只考「该不该查」，不把 TECH_KB 等细类当成必须命中的标签。 */
export function toRelatedOnlyIntentCase(item: {
  question: string;
  expectedIntent?: string;
  expectedRelated?: boolean;
}) {
  if (intentGateValue(item.expectedIntent, item.expectedRelated) === 'OFF_TOPIC') {
    return { question: item.question, expectedIntent: 'OFF_TOPIC', expectedRelated: false };
  }
  return { question: item.question, expectedRelated: true };
}

const METRIC_LABELS: Record<string, string> = {
  overallScore: '综合分',
  intentAccuracy: '问题分类准确率',
  intentMacroF1: '各类问题均衡得分',
  ragHitRate: 'Hit@K',
  ragMrr: 'MRR',
  ragNdcg: 'nDCG',
  retrievalRecall: 'Context Recall',
  retrievalMrr: 'MRR',
  retrievalNdcg: 'nDCG',
  retrievalPrecision: 'Context Precision',
  citationCoverage: 'Context Precision',
  groundedness: 'Faithfulness',
  answerQuality: 'Answer Correctness',
  answerRelevance: 'Answer Relevancy',
  answerAccuracy: 'Faithfulness',
  answerCompleteness: '要点是否说全',
  answerHelpfulness: '对面试准备是否有用',
  judgePassRate: '回答达标率',
  judgeAverageOverall: 'Answer Correctness',
  judgeAverageRelevance: 'Answer Relevancy',
  judgeAverageAccuracy: 'Faithfulness',
  judgeAverageCompleteness: '要点是否说全',
  judgeAverageHelpfulness: '对面试准备是否有用',
};

export function intentLabel(code: string | null | undefined, related?: boolean | null): string {
  if (related === false || code === 'OFF_TOPIC') return '闲聊';
  if (related === true || !code || RELATED_INTENTS.has(code)) return '查资料';
  return '查资料';
}

export function metricLabel(key: string): string {
  return METRIC_LABELS[key] ?? key;
}

export function pct(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}

export function num(value: number): string {
  return value.toFixed(3);
}

const FAILURE_PATTERN = /^(\w+)=([0-9.]+)\s*<\s*([0-9.]+)$/;

export function humanizeFailure(raw: string): string {
  const match = raw.trim().match(FAILURE_PATTERN);
  if (!match) return raw;
  return `${metricLabel(match[1])} ${pct(Number(match[2]))}，低于门槛 ${pct(Number(match[3]))}`;
}

export function humanizeFailures(failures: string[] | undefined): string[] {
  return (failures ?? []).map(humanizeFailure);
}

export function gateTone(
  id: EvalGateId,
  result: EvalRunResponse | null,
  options: { hasRetrieveCases: boolean; hasKnowledgeBase: boolean },
): FlowTone {
  if (!result) return 'skip';
  if (id === 'intent') {
    if (!result.intent || result.intent.total === 0) return 'skip';
    return result.intent.items.every(item => item.correct) ? 'ok' : 'fail';
  }
  if (id === 'retrieve') {
    if (!options.hasRetrieveCases) return 'skip';
    if (!options.hasKnowledgeBase) return 'warn';
    if (!result.rag || result.rag.total === 0) return 'warn';
    const retrieveGateFailed = (result.qualityGate?.failures ?? []).some(item =>
      item.startsWith('retrieval') || item.startsWith('citation'));
    if (retrieveGateFailed || result.rag.items.some(item => !item.hit)) return 'fail';
    return 'ok';
  }
  if (id === 'judge') {
    if (!result.judge || result.judge.total === 0) return 'skip';
    return result.judge.items.every(item => item.passed) ? 'ok' : 'fail';
  }
  if (!result.baselineComparison) return 'skip';
  return result.regression ? 'fail' : 'ok';
}

export function firstAttentionGate(result: EvalRunResponse): EvalGateId {
  if (result.intent && result.intent.items.some(item => !item.correct)) return 'intent';
  const retrieveGateFailed = (result.qualityGate?.failures ?? []).some(item =>
    item.startsWith('retrieval') || item.startsWith('citation'));
  if (result.rag && (retrieveGateFailed || result.rag.items.some(item => !item.hit))) return 'retrieve';
  if (result.judge && result.judge.items.some(item => !item.passed)) return 'judge';
  if (result.regression) return 'baseline';
  return 'intent';
}

export function qualityGateSummary(gate: QualityGate | undefined): { title: string; detail: string; tone: FlowTone } {
  if (!gate) {
    return {
      tone: 'skip',
      title: '还没有质量门结果',
      detail: '跑完后会按检索、生成、端到端汇总有没有低于后台门槛。',
    };
  }
  if (gate.passed) {
    return {
      tone: 'ok',
      title: '这组例题过了质量门',
      detail: '意图门、Context Precision / Recall、Faithfulness 与回答质量都达到当前门槛。门槛来自后端配置。',
    };
  }
  const failures = humanizeFailures(gate.failures);
  return {
    tone: 'fail',
    title: '有指标低于门槛，需要看是哪一道门',
    detail: failures.length > 0
      ? `未过项：${failures.join('；')}`
      : '有指标低于门槛，点下面的步骤看具体题目。',
  };
}

export function formatEvalReport(result: EvalRunResponse): string {
  const lines: string[] = [
    `# ${result.title}`,
    `时间: ${result.createdAt}`,
    `综合分: ${pct(result.overallScore)}${result.regression ? '（低于历史基准）' : ''}`,
    '',
    '## 标准 RAG 五指标（本页映射）',
  ];
  for (const metric of buildStandardRagMetrics(result)) {
    lines.push(`- ${metric.name}（${metric.zh}）: ${metric.value == null ? '未跑' : pct(metric.value)} · ${metric.how}`);
  }
  lines.push('');
  if (result.intent && result.intent.total > 0) {
    lines.push(
      '## 1. 问题 q · 该不该检索',
      `准确率 ${pct(result.intent.accuracy)} · 各类均衡 ${num(result.intent.macroF1)} · ${result.intent.correct}/${result.intent.total}`,
    );
    for (const item of result.intent.items) {
      lines.push(
        `- [${item.correct ? '对' : '错'}] ${item.question} → 期望=${intentLabel(item.expectedIntent, item.expectedRelated)} 实际=${intentLabel(item.actualIntent, item.actualRelated)}`,
      );
    }
    lines.push('');
  }
  if (result.rag) {
    lines.push(
      '## 2. 检索上下文 c(q)',
      `Context Precision ${pct(result.rag.retrievalPrecision)} · Context Recall ${pct(result.rag.retrievalRecall)} · Hit@${result.rag.k} ${pct(result.rag.hitRate)} · MRR ${num(result.rag.mrr)} · nDCG ${num(result.rag.ndcg)}`,
    );
    for (const item of result.rag.items) {
      const matched = item.matchedKeywords?.length
        ? `命中词 ${item.matchedKeywords.join('、')}`
        : item.hit ? '有关键词命中' : '未命中期望词';
      const missing = item.missingKeywords?.length ? `；缺 ${item.missingKeywords.join('、')}` : '';
      lines.push(
        `- [${item.hit ? '命中' : '未命中'}] ${item.question} · 首次命中第 ${item.firstHitRank} 条 · ${matched}${missing}`,
      );
      for (const segment of (item.retrievedSegments ?? []).slice(0, 3)) {
        lines.push(`  - #${segment.rank} ${segment.snippet || '(无摘要)'}`);
      }
    }
    lines.push('');
  }
  if (result.judge && result.judge.total > 0) {
    lines.push(
      '## 3. 生成答案 a',
      `Faithfulness ${pct(result.judge.averageAccuracy)} · Relevancy ${pct(result.judge.averageRelevance)} · Correctness ${pct(result.judge.averageOverall)} · 达标 ${result.judge.passed}/${result.judge.total}`,
    );
    for (const item of result.judge.items) {
      lines.push(
        `- [${item.passed ? '达标' : '未达标'}] ${item.question} · 总分=${num(item.overall)}（门槛 ${num(item.minOverallScore)}）`,
      );
    }
    lines.push('');
  }
  if (result.baselineComparison) {
    lines.push(
      '## 4. 和上次比',
      `允许波动 ${num(result.baselineComparison.threshold)}`,
    );
    for (const metric of result.baselineComparison.metrics) {
      lines.push(
        `- ${metricLabel(metric.metric)}: 当前 ${num(metric.current)} vs 基准 ${num(metric.baseline)}（Δ ${num(metric.delta)}）${metric.regressed ? ' [退步]' : ''}`,
      );
    }
    lines.push('');
  }
  if (result.qualityGate) {
    lines.push(
      '## 质量门',
      result.qualityGate.passed ? '通过' : `未通过：${humanizeFailures(result.qualityGate.failures).join('；')}`,
    );
  }
  lines.push(
    '',
    '复现：侧栏「RAG 评测」→ 勾面渣 Redis / JVM → 运行评测。',
    '本页默认是八股烟测。完整离线黄金集见 eval/rag/README.md。',
  );
  return lines.join('\n');
}
