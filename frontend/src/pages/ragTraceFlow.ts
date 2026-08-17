import type {
  RagTraceCandidate,
  RagTraceCitation,
  RagTraceDetail,
  RagTraceStage,
} from '../api/ragTrace';

/** LangSmith / Langfuse observation kinds. */
export type ObservationType = 'chain' | 'span' | 'retriever' | 'generation';

export type ObservationStatus = 'ok' | 'warn' | 'fail';

export type FlowTone = 'ok' | 'warn' | 'fail' | 'skip';

export interface TraceDocument {
  rank: number;
  content: string;
  source?: string;
  score?: number;
  rerankScore?: number;
  cited: boolean;
  valid: boolean;
  junk: boolean;
}

export interface TraceField {
  label: string;
  value: string;
}

export interface TraceObservation {
  id: string;
  type: ObservationType;
  name: string;
  title: string;
  summary: string;
  input: string;
  output: string;
  documents: TraceDocument[];
  status: ObservationStatus;
  latencyMs: number | null;
  latencyLabel: string;
  fields: TraceField[];
  children: TraceObservation[];
  startedAt: string | null;
  offsetMs: number;
}

export interface RagTraceTree {
  question: string;
  latencyLabel: string;
  statusLabel: string;
  note: string;
  totalMs: number;
  root: TraceObservation;
  children: TraceObservation[];
}

const STEP_TITLES: Record<string, string> = {
  rag_pipeline: '整次问答',
  intent: '意图',
  rewrite: '改写',
  route: '路由',
  retrieve: '检索',
  retrieval: '检索',
  rerank: '精排',
  generate: '生成',
  citation: '引用',
};

const TYPE_LABELS: Record<ObservationType, string> = {
  chain: '链路',
  span: '步骤',
  retriever: '检索',
  generation: '生成',
};

const SOURCE_LABELS: Record<string, string> = {
  knowledge_base: '知识库文档',
  mysql: '结构化数据（SQL）',
  relational_db: '结构化数据（SQL）',
  text2sql: '结构化数据（SQL）',
  sql: '结构化数据（SQL）',
  neo4j: '关系图谱',
  graph: '关系图谱',
  cypher: '关系图谱',
  text2cypher: '关系图谱',
  graph_db: '关系图谱',
  'interview.evidence': '面试证据',
};

export function observationTitle(name: string): string {
  const base = name.replace(/-\d+$/, '');
  return STEP_TITLES[base] ?? name;
}

export function observationTypeLabel(type: ObservationType): string {
  return TYPE_LABELS[type];
}

export function isOffTopicIntent(raw: string | null | undefined, related?: boolean | null): boolean {
  if (related === false) return true;
  if (related === true) return false;
  return (raw ?? '').trim().toUpperCase() === 'OFF_TOPIC';
}

/** 产品层只有两档：查知识库 / 不查。细类只换 Prompt，不在回放里当主标签。 */
export function labelIntent(raw: string | null | undefined, related?: boolean | null): string {
  if (!raw && related == null) return '未记录';
  return isOffTopicIntent(raw, related) ? '闲聊' : '查资料';
}

export function intentMeaning(raw: string | null | undefined, related?: boolean | null): string {
  return labelIntent(raw, related);
}

export function labelSource(raw: string | null | undefined): string {
  if (!raw) return '未记录';
  return SOURCE_LABELS[raw.trim().toLowerCase()] ?? raw;
}

export function labelGrounded(raw: string | null | undefined): string {
  if (!raw) return '未记录';
  const value = raw.trim().toLowerCase();
  if (value === 'pass' || value === 'grounded' || value === 'true') return '贴着资料';
  if (value === 'fail' || value === 'ungrounded' || value === 'false') return '没有贴着资料';
  if (value === 'partial') return '部分贴着资料';
  return raw;
}

export function labelRunStatus(status: string | null | undefined): string {
  if (status === 'DEGRADED') return '已降级';
  if (status === 'COMPLETED') return '已完成';
  if (status === 'FAILED' || status === 'ERROR') return '失败';
  if (status === 'RUNNING') return '进行中';
  return status || '未知';
}

export function formatLatency(ms: number | null | undefined): string {
  if (ms == null || ms < 0) return '—';
  if (ms < 1000) return `${ms} 毫秒`;
  return `${(ms / 1000).toFixed(1)} 秒`;
}

export function formatConfidence(raw: string | number | null | undefined): string {
  if (raw == null || raw === '') return '—';
  const value = typeof raw === 'number' ? raw : Number(raw);
  if (!Number.isFinite(value)) return String(raw);
  if (value <= 1) return `${Math.round(value * 100)}%`;
  return String(raw);
}

export function looksLikeNavJunk(text: string | null | undefined): boolean {
  if (!text) return false;
  const value = text.toLowerCase();
  const urls = value.match(/https?:\/\//g)?.length ?? 0;
  return urls >= 2 || value.includes('/sidebar/') || value.includes('javabetter');
}

export function parseIntentOutput(output: string | null | undefined): TraceField[] {
  if (!output) return [];
  const fields: TraceField[] = [];
  const intent = output.match(/^([A-Z][A-Z0-9_]+)/)?.[1];
  const related = output.match(/related\s*=\s*(true|false)/i)?.[1];
  const confidence = output.match(/综合置信度\s*([0-9.]+)/)?.[1]
    ?? output.match(/confidence\s*[=:]\s*([0-9.]+)/i)?.[1];
  const llm = output.match(/llm\s*=\s*([A-Z][A-Z0-9_]*)\/([0-9.]+)/i);
  const vector = output.match(/vector\s*=\s*([A-Z][A-Z0-9_]*)\/([0-9.]+)/i);
  const rule = output.match(/rule\s*=\s*([A-Z][A-Z0-9_]*)\/([0-9.]+)/i);
  const relatedFlag = related == null ? null : related.toLowerCase() === 'true';
  const gate = labelIntent(intent, relatedFlag);
  if (intent || related) {
    fields.push({ label: '判定', value: gate });
    fields.push({ label: '是否查知识库', value: gate === '闲聊' ? '否' : '是' });
  }
  if (confidence) {
    fields.push({ label: '综合置信度', value: formatConfidence(confidence) });
  }
  if (llm) fields.push({ label: 'LLM', value: `${labelIntent(llm[1])} · ${formatConfidence(llm[2])}` });
  if (vector) fields.push({ label: '向量', value: `${labelIntent(vector[1])} · ${formatConfidence(vector[2])}` });
  if (rule) fields.push({ label: '规则', value: `${labelIntent(rule[1])} · ${formatConfidence(rule[2])}` });
  return fields;
}

export function inferQueryKind(
  input: string,
  question: string,
  rewritten: string,
  index: number,
  total: number,
): string {
  const query = normalizeQuery(input);
  const original = normalizeQuery(question);
  const rewrittenQuery = normalizeQuery(rewritten);
  if (query && original && query === original) return '原问题';
  if (query && rewrittenQuery && query === rewrittenQuery) return '改写查询';
  if (query.length >= 80 || (original && query.length > original.length * 2.5)) return 'HyDE 假设文档';
  if (total > 1) return `子查询 ${index}`;
  return '查询';
}

export function waterfallShare(
  offsetMs: number,
  latencyMs: number | null,
  totalMs: number,
): { left: number; width: number } {
  if (totalMs <= 0) return { left: 0, width: 0 };
  const latency = Math.max(0, latencyMs ?? 0);
  const left = Math.min(0.92, Math.max(0, offsetMs / totalMs));
  if (latency <= 0) return { left, width: 0 };
  return {
    left,
    width: Math.max(0.03, Math.min(1 - left, latency / totalMs)),
  };
}

function findStage(stages: RagTraceStage[], name: string): RagTraceStage | undefined {
  return stages.find(stage => stage.stage?.toUpperCase() === name);
}

function byStage(candidates: RagTraceCandidate[], name: string): RagTraceCandidate[] {
  return candidates
    .filter(item => item.stage?.toUpperCase() === name)
    .slice()
    .sort((a, b) => (a.rankNo ?? 0) - (b.rankNo ?? 0));
}

function compact(value: string | null | undefined, max = 80): string {
  if (!value) return '';
  const normalized = value.replace(/\s+/g, ' ').trim();
  return normalized.length > max ? `${normalized.slice(0, max)}…` : normalized;
}

function normalizeQuery(value: string | null | undefined): string {
  return (value ?? '').replace(/\s+/g, ' ').trim();
}

function citationFor(citations: RagTraceCitation[], index: number): RagTraceCitation | undefined {
  return citations.find(item => item.citationIndex === index);
}

function toDocuments(
  candidates: RagTraceCandidate[],
  citations: RagTraceCitation[],
): TraceDocument[] {
  return candidates.map(candidate => {
    const rank = candidate.rankNo ?? 0;
    const citation = citationFor(citations, rank);
    return {
      rank,
      content: candidate.snippet?.trim() || '',
      source: citation?.sourceLocator || undefined,
      score: candidate.score ?? undefined,
      rerankScore: candidate.rerankScore ?? undefined,
      cited: Boolean(citation?.cited),
      valid: citation ? citation.valid : true,
      junk: looksLikeNavJunk(candidate.snippet),
    };
  });
}

function observation(
  partial: Omit<TraceObservation, 'documents' | 'latencyMs' | 'latencyLabel' | 'fields' | 'children' | 'title' | 'startedAt' | 'offsetMs'> & {
    documents?: TraceDocument[];
    latencyMs?: number | null;
    latencyLabel?: string;
    fields?: TraceField[];
    children?: TraceObservation[];
    title?: string;
    startedAt?: string | null;
    offsetMs?: number;
  },
): TraceObservation {
  const latencyMs = partial.latencyMs ?? null;
  return {
    ...partial,
    title: partial.title ?? observationTitle(partial.name),
    documents: partial.documents ?? [],
    latencyMs,
    latencyLabel: partial.latencyLabel ?? formatLatency(latencyMs),
    fields: partial.fields ?? [],
    children: partial.children ?? [],
    startedAt: partial.startedAt ?? null,
    offsetMs: partial.offsetMs ?? 0,
  };
}

const PROCESS_STAGES = new Set([
  'ROUTE',
  'RETRIEVAL',
  'RETRIEVE',
  'RERANK',
  'GENERATE',
]);

export function hasProcessSpans(stages: RagTraceStage[]): boolean {
  return stages.some(stage => {
    const name = stage.stage?.toUpperCase() ?? '';
    return PROCESS_STAGES.has(name) || (stage.latencyMs ?? 0) > 0;
  });
}

function firstToken(value: string | null | undefined): string {
  return value?.trim().split(/\s+/)[0] ?? '';
}

function parseMetadata(metadataJson: string | null | undefined): Record<string, unknown> {
  if (!metadataJson) return {};
  try {
    const parsed = JSON.parse(metadataJson) as Record<string, unknown>;
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function observationTypeOf(stage: RagTraceStage, fallback: ObservationType): ObservationType {
  const type = parseMetadata(stage.metadataJson).observationType;
  if (type === 'span' || type === 'retriever' || type === 'generation' || type === 'chain') {
    return type;
  }
  return fallback;
}

function stageKind(stageName: string | null | undefined): { name: string; type: ObservationType } {
  switch ((stageName ?? '').toUpperCase()) {
    case 'INTENT':
      return { name: 'intent', type: 'span' };
    case 'REWRITE':
      return { name: 'rewrite', type: 'span' };
    case 'ROUTE':
      return { name: 'route', type: 'span' };
    case 'RETRIEVAL':
    case 'RETRIEVE':
      return { name: 'retrieve', type: 'retriever' };
    case 'RERANK':
      return { name: 'rerank', type: 'retriever' };
    case 'GENERATE':
      return { name: 'generate', type: 'generation' };
    case 'CITATION':
      return { name: 'citation', type: 'span' };
    default:
      return { name: (stageName ?? 'span').toLowerCase(), type: 'span' };
  }
}

function stageStatus(status: string | null | undefined): ObservationStatus {
  const value = (status ?? '').toUpperCase();
  if (value === 'FAILED' || value === 'FAIL' || value === 'ERROR') return 'fail';
  if (value === 'DEGRADED' || value === 'WARN') return 'warn';
  return 'ok';
}

function uniqueObservationId(base: string, used: Map<string, number>): string {
  const next = (used.get(base) ?? 0) + 1;
  used.set(base, next);
  return next === 1 ? base : `${base}-${next}`;
}

function citationDocuments(citations: RagTraceCitation[]): TraceDocument[] {
  return citations.map(item => ({
    rank: item.citationIndex,
    content: item.cited
      ? (item.valid ? '回答里引用了这条' : '引用编号对不上检索结果')
      : '检索到了但回答没引用',
    source: item.sourceLocator || undefined,
    cited: item.cited,
    valid: item.valid,
    junk: false,
  }));
}

function parseTime(value: string | null | undefined): number | null {
  if (!value) return null;
  const time = Date.parse(value);
  return Number.isFinite(time) ? time : null;
}

function rewrittenQuery(detail: RagTraceDetail): string {
  const stage = findStage(detail.stages, 'REWRITE');
  if (!stage) return '';
  if (hasProcessSpans(detail.stages)) {
    return (stage.outputSummary || '').trim();
  }
  return (stage.inputSummary || '').trim();
}

function fieldsFromMetadata(metadata: Record<string, unknown>): TraceField[] {
  const fields: TraceField[] = [];
  const intent = typeof metadata.intent === 'string' ? metadata.intent : null;
  const related = typeof metadata.related === 'boolean' ? metadata.related : null;
  if (intent || related != null) {
    const gate = labelIntent(intent, related);
    fields.push({ label: '判定', value: gate });
    fields.push({ label: '是否查知识库', value: gate === '闲聊' ? '否' : '是' });
  }
  if (typeof metadata.confidence === 'number') {
    fields.push({ label: '综合置信度', value: formatConfidence(metadata.confidence) });
  }
  return fields;
}

function intentFields(output: string, metadata: Record<string, unknown>): TraceField[] {
  const fromMeta = fieldsFromMetadata(metadata);
  const parsed = parseIntentOutput(output);
  return fromMeta.length >= parsed.length ? fromMeta : parsed;
}

function routeFields(stage: RagTraceStage, output: string): TraceField[] {
  const source = stage.dataSource || firstToken(output);
  const intent = output.trim().split(/\s+/)[1];
  const reason = output.replace(/^\S+(?:\s+\S+)?\s*/, '').trim();
  const fields: TraceField[] = [];
  if (source) fields.push({ label: '数据源', value: labelSource(source) });
  if (source) fields.push({ label: '源编码', value: source });
  if (intent && /^[A-Z][A-Z0-9_]+$/.test(intent)) {
    fields.push({ label: '是否查知识库', value: labelIntent(intent) === '闲聊' ? '否' : '是' });
  }
  const confidence = parseMetadata(stage.metadataJson).confidence;
  if (typeof confidence === 'number') {
    fields.push({ label: '置信度', value: formatConfidence(confidence) });
  }
  if (reason) fields.push({ label: '理由', value: compact(reason, 160) });
  return fields;
}

function wallClockOrSum(nodes: TraceObservation[]): number | null {
  const starts = nodes.map(node => parseTime(node.startedAt)).filter((value): value is number => value != null);
  const latencies = nodes.map(node => node.latencyMs).filter((value): value is number => value != null);
  if (starts.length === nodes.length && latencies.length === nodes.length) {
    const origin = Math.min(...starts);
    const end = Math.max(...starts.map((start, index) => start + (nodes[index].latencyMs ?? 0)));
    return Math.max(0, end - origin);
  }
  if (latencies.length === 0) return null;
  return latencies.reduce((sum, value) => sum + value, 0);
}

function nestRetrievalPipeline(children: TraceObservation[]): TraceObservation[] {
  const retrievalBase = new Set(['route', 'retrieve', 'rerank']);
  const head: TraceObservation[] = [];
  const middle: TraceObservation[] = [];
  const tail: TraceObservation[] = [];
  let phase: 'head' | 'mid' | 'tail' = 'head';

  for (const child of children) {
    const base = child.name.replace(/-\d+$/, '');
    if (retrievalBase.has(base) && phase !== 'tail') {
      phase = 'mid';
      middle.push(child);
    } else if (phase === 'head') {
      head.push(child);
    } else {
      phase = 'tail';
      tail.push(child);
    }
  }

  if (middle.length === 0) {
    return children;
  }

  const retrieveCount = middle.filter(item => item.name.replace(/-\d+$/, '') === 'retrieve').length;
  const rerank = [...middle].reverse().find(item => item.name.replace(/-\d+$/, '') === 'rerank');
  const lastRetrieve = [...middle].reverse().find(item => item.name.replace(/-\d+$/, '') === 'retrieve');
  const documents = rerank?.documents.length ? rerank.documents : (lastRetrieve?.documents ?? []);
  const parentLatency = wallClockOrSum(middle);

  return [
    ...head,
    observation({
      id: 'retrieval',
      type: 'retriever',
      name: 'retrieval',
      title: '检索',
      summary: retrieveCount > 0 ? `${retrieveCount} 路召回` : `${middle.length} 步`,
      input: middle[0]?.input ?? '',
      output: middle.map(item => item.title).join(' → '),
      documents,
      status: middle.some(item => item.status === 'fail')
        ? 'fail'
        : middle.some(item => item.status === 'warn') ? 'warn' : 'ok',
      latencyMs: parentLatency,
      startedAt: middle[0]?.startedAt ?? null,
      fields: [
        { label: '子步骤', value: String(middle.length) },
        { label: '召回路数', value: String(retrieveCount) },
        rerank ? { label: '精排', value: rerank.summary } : null,
      ].filter((item): item is TraceField => item != null),
      children: middle,
    }),
    ...tail,
  ];
}

function applyOffsets(nodes: TraceObservation[], originMs: number | null): TraceObservation[] {
  return nodes.map(node => {
    const start = parseTime(node.startedAt);
    return {
      ...node,
      offsetMs: originMs != null && start != null ? Math.max(0, start - originMs) : 0,
      children: applyOffsets(node.children, originMs),
    };
  });
}

function buildChildrenFromStages(detail: RagTraceDetail): TraceObservation[] {
  const retrieved = byStage(detail.candidates, 'RETRIEVAL');
  const reranked = byStage(detail.candidates, 'RERANK');
  const cited = detail.citations.filter(item => item.cited).length;
  const unused = detail.citations.filter(item => item.valid && !item.cited).length;
  const invalid = detail.citations.filter(item => !item.valid).length;
  const used = new Map<string, number>();
  const retrieveIndexes = detail.stages
    .map((stage, index) => ((stage.stage ?? '').toUpperCase() === 'RETRIEVAL'
      || (stage.stage ?? '').toUpperCase() === 'RETRIEVE' ? index : -1))
    .filter(index => index >= 0);
  const lastRetrieveIndex = retrieveIndexes[retrieveIndexes.length - 1];
  const rewritten = rewrittenQuery(detail);
  const retrieveInputs = detail.stages
    .filter(stage => {
      const name = (stage.stage ?? '').toUpperCase();
      return name === 'RETRIEVAL' || name === 'RETRIEVE';
    })
    .map(stage => stage.inputSummary?.trim() || detail.run.question);
  const routeInputs = detail.stages
    .filter(stage => (stage.stage ?? '').toUpperCase() === 'ROUTE')
    .map(stage => stage.inputSummary?.trim() || '');
  let retrieveOrdinal = 0;
  let routeOrdinal = 0;

  const leaves = detail.stages.map((stage, index) => {
    const kind = stageKind(stage.stage);
    const id = uniqueObservationId(kind.name, used);
    const input = stage.inputSummary?.trim() || detail.run.question;
    const output = stage.outputSummary?.trim() || '';
    const metadata = parseMetadata(stage.metadataJson);
    let summary = compact(output, 48) || kind.name;
    let title = observationTitle(kind.name);
    let documents: TraceDocument[] = [];
    let status = stageStatus(stage.status);
    let fields: TraceField[] = [];

    if (kind.name === 'intent') {
      const raw = firstToken(output) || (typeof metadata.intent === 'string' ? metadata.intent : '') || detail.run.routeIntent || '';
      const relatedFlag = typeof metadata.related === 'boolean'
        ? metadata.related
        : /related\s*=\s*true/i.test(output) ? true
          : /related\s*=\s*false/i.test(output) ? false
            : null;
      fields = intentFields(output, metadata);
      const gate = labelIntent(raw, relatedFlag);
      title = raw || relatedFlag != null ? `意图 · ${gate}` : '意图';
      summary = fields.find(item => item.label === '综合置信度')?.value || gate;
    } else if (kind.name === 'rewrite') {
      const rewrittenText = output || input;
      const same = rewrittenText === detail.run.question.trim();
      title = same ? '改写 · 未改写' : '改写';
      summary = same ? '未改写' : compact(rewrittenText, 48);
      fields = [
        { label: '原问题', value: detail.run.question },
        { label: '改写结果', value: rewrittenText },
        { label: '是否改写', value: same ? '否' : '是' },
      ];
    } else if (kind.name === 'route') {
      routeOrdinal += 1;
      const kindLabel = inferQueryKind(input, detail.run.question, rewritten, routeOrdinal, routeInputs.length);
      title = `路由 · ${kindLabel}`;
      summary = labelSource(stage.dataSource || firstToken(output) || detail.run.routeSource);
      fields = [
        { label: '查询类型', value: kindLabel },
        ...routeFields(stage, output),
      ];
    } else if (kind.name === 'retrieve') {
      retrieveOrdinal += 1;
      const kindLabel = inferQueryKind(input, detail.run.question, rewritten, retrieveOrdinal, retrieveInputs.length);
      title = `检索 · ${kindLabel}`;
      summary = output || `${retrieved.length} 条`;
      fields = [
        { label: '查询类型', value: kindLabel },
        { label: '命中', value: output || '未记录' },
        { label: '查询', value: compact(input, 160) },
      ];
      if (index === lastRetrieveIndex) {
        documents = toDocuments(retrieved, detail.citations);
        status = retrieved.length > 0 ? status : 'warn';
        if (retrieved.length > 0) {
          fields.push({ label: '文档', value: `${retrieved.length} 条（最后一次召回快照）` });
        }
      } else {
        fields.push({ label: '文档', value: '各路召回只保留最后一次快照' });
      }
    } else if (kind.name === 'rerank') {
      title = '精排';
      summary = output || `${reranked.length} 条`;
      documents = toDocuments(reranked, detail.citations);
      fields = [
        { label: '结果', value: output || `${reranked.length} 条` },
        { label: '文档', value: `${reranked.length} 条` },
      ];
    } else if (kind.name === 'generate') {
      title = '生成';
      summary = compact(output || detail.answer?.answer, 48);
      fields = [
        { label: '问题', value: detail.run.question },
        { label: '上下文', value: reranked.length > 0 ? `精排后 ${reranked.length} 条` : '无' },
        { label: '字数', value: String((output || detail.answer?.answer || '').length) },
      ];
    } else if (kind.name === 'citation') {
      title = '引用';
      summary = invalid > 0 ? `${invalid} 条无效` : `${cited} 条已引用 / ${unused} 条未引用`;
      documents = citationDocuments(detail.citations);
      if (invalid > 0) status = 'fail';
      fields = [
        { label: '已引用', value: String(cited) },
        { label: '未引用', value: String(unused) },
        { label: '无效编号', value: String(invalid) },
        { label: '是否贴着资料', value: labelGrounded(detail.answer?.groundedStatus) },
        detail.answer?.confidence != null
          ? { label: '置信度', value: formatConfidence(detail.answer.confidence) }
          : null,
      ].filter((item): item is TraceField => item != null);
    }

    return observation({
      id,
      type: observationTypeOf(stage, kind.type),
      name: id,
      title,
      summary,
      input,
      output: output || summary,
      documents,
      status,
      latencyMs: stage.latencyMs,
      startedAt: stage.startedAt,
      fields,
    });
  });

  return nestRetrievalPipeline(leaves);
}

function reconstructChildren(detail: RagTraceDetail): TraceObservation[] {
  const intentStage = findStage(detail.stages, 'INTENT');
  const rewriteStage = findStage(detail.stages, 'REWRITE');
  const citationStage = findStage(detail.stages, 'CITATION');
  const retrieved = byStage(detail.candidates, 'RETRIEVAL');
  const reranked = byStage(detail.candidates, 'RERANK');
  const rewritten = rewriteStage?.inputSummary?.trim() || '';
  const intentRaw = intentStage?.inputSummary || detail.run.routeIntent;
  const sourceRaw = intentStage?.dataSource || detail.run.routeSource;
  const answer = detail.answer?.answer?.trim() || '';
  const cited = detail.citations.filter(item => item.cited).length;
  const unused = detail.citations.filter(item => item.valid && !item.cited).length;
  const invalid = detail.citations.filter(item => !item.valid).length;
  const children: TraceObservation[] = [];

  if (intentRaw) {
    const output = `${labelIntent(intentRaw)}${intentStage?.outputSummary ? `\n${intentStage.outputSummary}` : ''}`;
    children.push(observation({
      id: 'intent',
      type: 'span',
      name: 'intent',
      title: `意图 · ${labelIntent(intentRaw)}`,
      summary: labelIntent(intentRaw),
      input: detail.run.question,
      output,
      status: 'ok',
      fields: [
        { label: '判定', value: labelIntent(intentRaw) },
        { label: '是否查知识库', value: labelIntent(intentRaw) === '闲聊' ? '否' : '是' },
        sourceRaw ? { label: '数据源', value: labelSource(sourceRaw) } : null,
      ].filter((item): item is TraceField => item != null),
    }));
  }

  if (rewritten) {
    const same = rewritten === detail.run.question.trim();
    children.push(observation({
      id: 'rewrite',
      type: 'span',
      name: 'rewrite',
      title: same ? '改写 · 未改写' : '改写',
      summary: same ? '未改写' : compact(rewritten, 48),
      input: detail.run.question,
      output: rewritten,
      status: 'ok',
      fields: [
        { label: '原问题', value: detail.run.question },
        { label: '改写结果', value: rewritten },
        { label: '是否改写', value: same ? '否' : '是' },
      ],
    }));
  }

  if (sourceRaw) {
    children.push(observation({
      id: 'route',
      type: 'span',
      name: 'route',
      title: '路由 · 主查询',
      summary: labelSource(sourceRaw),
      input: labelIntent(intentRaw),
      output: labelSource(sourceRaw),
      status: 'ok',
      fields: [
        { label: '数据源', value: labelSource(sourceRaw) },
        { label: '源编码', value: sourceRaw },
      ],
    }));
  }

  if (retrieved.length > 0 || sourceRaw) {
    children.push(observation({
      id: 'retrieve',
      type: 'retriever',
      name: 'retrieve',
      title: '检索 · 查询',
      summary: `${retrieved.length} 条`,
      input: rewritten || detail.run.question,
      output: retrieved.length > 0
        ? retrieved.map((item, index) => `#${item.rankNo ?? index + 1} ${compact(item.snippet, 60)}`).join('\n')
        : '[]',
      documents: toDocuments(retrieved, detail.citations),
      status: retrieved.length > 0 ? 'ok' : 'warn',
      fields: [
        { label: '命中', value: `${retrieved.length} 条` },
        { label: '查询', value: rewritten || detail.run.question },
      ],
    }));
  }

  if (reranked.length > 0) {
    children.push(observation({
      id: 'rerank',
      type: 'retriever',
      name: 'rerank',
      title: '精排',
      summary: `${reranked.length} 条`,
      input: rewritten || detail.run.question,
      output: reranked.map((item, index) => `#${item.rankNo ?? index + 1} ${compact(item.snippet, 60)}`).join('\n'),
      documents: toDocuments(reranked, detail.citations),
      status: 'ok',
      fields: [{ label: '文档', value: `${reranked.length} 条` }],
    }));
  }

  if (answer) {
    children.push(observation({
      id: 'generate',
      type: 'generation',
      name: 'generate',
      title: '生成',
      summary: compact(answer, 48),
      input: [
        `问题：${detail.run.question}`,
        reranked.length > 0 ? `上下文：精排后 ${reranked.length} 条` : '上下文：无',
      ].join('\n'),
      output: answer,
      status: 'ok',
      fields: [
        { label: '问题', value: detail.run.question },
        { label: '上下文', value: reranked.length > 0 ? `精排后 ${reranked.length} 条` : '无' },
        { label: '字数', value: String(answer.length) },
      ],
    }));
  }

  if (detail.citations.length > 0) {
    children.push(observation({
      id: 'citation',
      type: 'span',
      name: 'citation',
      title: '引用',
      summary: invalid > 0 ? `${invalid} 条无效` : `${cited} 条已引用 / ${unused} 条未引用`,
      input: answer || detail.run.question,
      output: [
        `已引用 ${cited} 条`,
        `未引用 ${unused} 条`,
        `无效 ${invalid} 条`,
        `是否贴着资料：${labelGrounded(citationStage?.outputSummary || detail.answer?.groundedStatus)}`,
        detail.answer?.confidence != null
          ? `置信度 ${formatConfidence(detail.answer.confidence)}`
          : null,
      ].filter(Boolean).join('\n'),
      documents: citationDocuments(detail.citations),
      status: invalid > 0 ? 'fail' : 'ok',
      fields: [
        { label: '已引用', value: String(cited) },
        { label: '未引用', value: String(unused) },
        { label: '无效编号', value: String(invalid) },
        { label: '是否贴着资料', value: labelGrounded(citationStage?.outputSummary || detail.answer?.groundedStatus) },
      ],
    }));
  }

  return nestRetrievalPipeline(children);
}

export function flattenObservations(nodes: TraceObservation[]): TraceObservation[] {
  return nodes.flatMap(node => [node, ...flattenObservations(node.children)]);
}

export function buildRagTraceTree(detail: RagTraceDetail): RagTraceTree {
  const answer = detail.answer?.answer?.trim() || '';
  const invalid = detail.citations.filter(item => !item.valid).length;
  const timed = hasProcessSpans(detail.stages);
  const children = timed ? buildChildrenFromStages(detail) : reconstructChildren(detail);
  const originMs = children
    .flatMap(node => flattenObservations([node]))
    .map(node => parseTime(node.startedAt))
    .filter((value): value is number => value != null)
    .reduce<number | null>((min, value) => (min == null || value < min ? value : min), null);
  const shifted = applyOffsets(children, originMs);
  const root = observation({
    id: 'root',
    type: 'chain',
    name: 'rag_pipeline',
    title: '整次问答',
    summary: compact(answer, 48) || compact(detail.run.question, 48),
    input: detail.run.question,
    output: answer || '（无回答快照）',
    status: detail.run.status === 'DEGRADED' || invalid > 0 ? 'fail' : 'ok',
    latencyMs: detail.run.latencyMs,
    startedAt: detail.run.createdAt,
    fields: [
      { label: '问题', value: detail.run.question },
      { label: '状态', value: labelRunStatus(detail.run.status) },
      { label: '总耗时', value: formatLatency(detail.run.latencyMs) },
      detail.run.routeIntent ? { label: '判定', value: labelIntent(detail.run.routeIntent) } : null,
      detail.run.routeSource ? { label: '数据源', value: labelSource(detail.run.routeSource) } : null,
    ].filter((item): item is TraceField => item != null),
  });

  return {
    question: detail.run.question,
    latencyLabel: formatLatency(detail.run.latencyMs),
    statusLabel: labelRunStatus(detail.run.status),
    totalMs: Math.max(detail.run.latencyMs ?? 0, 1),
    note: timed
      ? '点开某一步看字段、输入和输出。检索下的多路是改写 / 分解 / HyDE 的多次调用。'
      : '这是答完后的快照回放；旧记录没有逐步耗时。',
    root,
    children: shifted,
  };
}

export function allObservations(tree: RagTraceTree): TraceObservation[] {
  return [tree.root, ...flattenObservations(tree.children)];
}
