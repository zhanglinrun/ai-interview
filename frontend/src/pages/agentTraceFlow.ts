import type {
  AgentTraceAct,
  AgentTraceCatalogItem,
  AgentTraceEvent,
  AgentTracePlayback,
  AgentTraceSpan,
} from '../types/interview';
import type { FlowTone } from './ragTraceFlow';

export interface AgentFlowDiagnosis {
  tone: FlowTone;
  title: string;
  detail: string;
  warnings?: string[];
}

export interface AgentSpanView {
  span: AgentTraceSpan;
  depth: number;
}

export function labelSessionStatus(status: string | null | undefined): string {
  const labels: Record<string, string> = {
    CREATED: '刚创建',
    READY: '已准备',
    IN_PROGRESS: '进行中',
    PAUSED: '已暂停',
    COMPLETING: '收尾中',
    COMPLETED: '已完成',
    EVALUATED: '已评估',
    ABORTED: '已中止',
    FAILED: '失败',
  };
  if (!status) return '未知状态';
  return labels[status] ?? status;
}

export function catalogHasProcess(item: AgentTraceCatalogItem): boolean {
  return item.stepCount > 0;
}

export function catalogOptionLabel(item: AgentTraceCatalogItem, dateText: string): string {
  const kind = item.orphanRun ? '编排运行' : '模拟面试';
  return `${kind} · ${labelSessionStatus(item.status)} · ${item.stepCount} 步${dateText ? ` · ${dateText}` : ''}`;
}

export function resolveSpans(playback: AgentTracePlayback | null | undefined): AgentTraceSpan[] {
  if (playback?.spans?.length) {
    return playback.spans;
  }
  return fallbackSpansFromActs(playback?.acts);
}

export function flattenSpans(spans: AgentTraceSpan[] | null | undefined, depth = 0): AgentSpanView[] {
  if (!spans?.length) return [];
  return spans.flatMap(span => [
    { span, depth },
    ...flattenSpans(span.children ?? [], depth + 1),
  ]);
}

function fallbackSpansFromActs(acts: AgentTraceAct[] | null | undefined): AgentTraceSpan[] {
  if (!acts?.length) return [];
  return acts.flatMap((act, actIndex) =>
    (act.events ?? []).map((event, eventIndex) => spanFromLegacyEvent(event, `${actIndex}-${eventIndex}`)),
  );
}

function spanFromLegacyEvent(event: AgentTraceEvent, key: string): AgentTraceSpan {
  const kind = event.action === 'chat'
    ? 'chat'
    : event.headline?.startsWith('Tool ·') || (event.role === 'interviewer' && event.action === 'readResume')
      ? 'tool'
      : 'agent';
  return {
    spanId: `legacy-${key}`,
    parentSpanId: null,
    kind,
    role: event.role,
    action: event.action,
    title: event.headline || event.action || 'step',
    input: event.input ?? null,
    output: event.body || null,
    status: event.approved === false ? 'FAILED' : 'COMPLETED',
    latencyMs: null,
    model: null,
    inputTokens: null,
    outputTokens: null,
    questionIndex: event.questionIndex,
    children: [],
  };
}

export function spanTone(span: AgentTraceSpan): FlowTone {
  if (span.status === 'FAILED' || span.action === 'ask_failed' || span.action === 'grounding_reject') {
    return 'fail';
  }
  if (span.action === 'critique' && /未通过|打回|false/i.test(span.output || '')) {
    return 'fail';
  }
  if (span.kind === 'chat') return 'ok';
  if (span.kind === 'tool') return 'warn';
  return 'ok';
}

export function diagnoseTrace(_playback: AgentTracePlayback, views: AgentSpanView[]): AgentFlowDiagnosis {
  const chats = views.filter(item => item.span.kind === 'chat').length;
  const tools = views.filter(item => item.span.kind === 'tool').length;
  if (views.length === 0) {
    return {
      tone: 'skip',
      title: '还没有可回放的 span',
      detail: '开一场文字模拟面试。创建和下题时会在模型调用边界写下 chat / tool 节点。',
    };
  }
  if (chats === 0) {
    const warnings = collectTraceWarnings(views);
    return {
      tone: 'warn',
      title: `这场只有 ${views.length} 条编排记录，没有 chat span`,
      detail: warnings.length
        ? warnings.join(' ')
        : '这是旧场次或没有 span 树的编排运行。要看标准树，请新开一场文字模拟面试再出题。',
      warnings,
    };
  }
  const warnings = collectTraceWarnings(views);
  return {
    tone: warnings.length ? 'warn' : 'ok',
    title: `这条 trace 有 ${chats} 次模型调用` + (tools ? `、${tools} 次工具` : ''),
    detail: warnings.length
      ? warnings.join(' ')
      : '定大纲是 Planner 的 Chat；第 1 题才是 Interviewer / Critic。点 Chat 看截断后的输入和输出。',
    warnings,
  };
}

function collectTraceWarnings(views: AgentSpanView[]): string[] {
  const warnings: string[] = [];
  const phases = views.filter(item => item.depth === 0);
  for (const phase of phases) {
    const children = flattenSpans(phase.span.children ?? [], 1);
    const hasAsk = children.some(item => item.span.action === 'ask' || item.span.action === 'critique');
    const hasChat = children.some(item => item.span.kind === 'chat');
    if (hasAsk && !hasChat) {
      warnings.push(`${phase.span.title} 有 ask/critique 但没有对应 Chat。`);
    }
    const hasDecision = children.some(item => item.span.action === 'turn_decision');
    const toolCount = children.filter(item => item.span.kind === 'tool').length;
    if (hasDecision && toolCount === 0) {
      warnings.push(`${phase.span.title} 有逐轮决策但 tools=0。`);
    }
    if (phase.span.action === 'evaluating' || phase.span.title === '评估') {
      const enqueued = children.some(item => item.span.action === 'enqueue_evaluation');
      const finished = children.some(item =>
        item.span.action === 'evaluate_completed' || item.span.action === 'evaluate_failed');
      if (enqueued && !finished) {
        warnings.push('评估相只有入队，没有完成或失败。');
      }
    }
  }
  if (views.some(item => item.span.action === 'reflexion_limit')) {
    warnings.push('出现 Reflexion 达上限。');
  }
  return warnings;
}

export function defaultSpanId(views: AgentSpanView[]): string {
  return views.find(item => item.span.kind === 'chat')?.span.spanId
    ?? views[0]?.span.spanId
    ?? '';
}
