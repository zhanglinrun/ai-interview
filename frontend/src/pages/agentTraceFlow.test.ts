import { describe, expect, it } from 'vitest';
import type { AgentTracePlayback, AgentTraceSpan } from '../types/interview';
import { diagnoseTrace, flattenSpans, resolveSpans } from './agentTraceFlow';

function span(partial: Partial<AgentTraceSpan>): AgentTraceSpan {
  return {
    spanId: 's1',
    parentSpanId: null,
    kind: 'agent',
    role: 'orchestrator',
    action: 'turn_decision',
    title: 'turn_decision',
    input: 'in',
    output: 'out',
    status: 'COMPLETED',
    latencyMs: 1,
    model: null,
    inputTokens: null,
    outputTokens: null,
    questionIndex: null,
    children: [],
    ...partial,
  };
}

function playback(spans: AgentTraceSpan[]): AgentTracePlayback {
  return {
    sessionId: 's',
    sourceIds: ['s'],
    agentMode: true,
    stepCount: spans.length,
    reflexionRounds: 0,
    criticRejects: 0,
    groundingRejects: 0,
    toolCalls: 0,
    emptyReason: null,
    emptyHint: null,
    plan: null,
    acts: [],
    spans,
  };
}

describe('agentTraceFlow', () => {
  it('flattens nested chat/tool spans for the tree', () => {
    const views = flattenSpans([
      span({
        spanId: 'chat',
        kind: 'chat',
        title: 'Chat · Interviewer',
        children: [span({ spanId: 'tool', kind: 'tool', title: 'Tool · readResume' })],
      }),
    ]);
    expect(views.map(item => item.span.spanId)).toEqual(['chat', 'tool']);
    expect(views[1].depth).toBe(1);
  });

  it('diagnoses a standard chat/tool trace', () => {
    const views = flattenSpans([
      span({ spanId: 'c1', kind: 'chat', title: 'Chat · Interviewer', children: [
        span({ spanId: 't1', kind: 'tool', title: 'Tool · readResume' }),
      ] }),
    ]);
    expect(diagnoseTrace(playback(views.map(item => item.span)), views).title).toContain('1 次模型调用');
  });

  it('warns when a question has ask/critique without chat', () => {
    const views = flattenSpans([
      span({
        spanId: 'q2',
        title: '第 2 题',
        action: 'question',
        children: [
          span({ spanId: 'ask', action: 'ask', title: 'ask' }),
          span({ spanId: 'cr', action: 'critique', title: 'critique' }),
        ],
      }),
    ]);
    const diagnosis = diagnoseTrace(playback(views.map(item => item.span)), views);
    expect(diagnosis.tone).toBe('warn');
    expect(diagnosis.warnings?.some(item => item.includes('ask/critique'))).toBe(true);
  });

  it('warns on reflexion_limit and evaluation enqueue without completion', () => {
    const views = flattenSpans([
      span({
        spanId: 'q1',
        title: '第 1 题',
        action: 'question',
        children: [
          span({ spanId: 'c1', kind: 'chat', title: 'Chat · Interviewer' }),
          span({ spanId: 'td', action: 'turn_decision', title: '逐轮决策' }),
          span({ spanId: 'lim', action: 'reflexion_limit', title: 'Reflexion 达上限' }),
        ],
      }),
      span({
        spanId: 'eval',
        title: '评估',
        action: 'evaluating',
        children: [
          span({ spanId: 'enq', action: 'enqueue_evaluation', title: '评估入队' }),
        ],
      }),
    ]);
    const diagnosis = diagnoseTrace(playback(views.map(item => item.span)), views);
    expect(diagnosis.warnings?.some(item => item.includes('Reflexion'))).toBe(true);
    expect(diagnosis.warnings?.some(item => item.includes('tools=0'))).toBe(true);
    expect(diagnosis.warnings?.some(item => item.includes('评估相只有入队'))).toBe(true);
  });

  it('falls back to act events when spans are missing', () => {
    const next = resolveSpans({
      ...playback([]),
      spans: [],
      acts: [{
        questionIndex: 0,
        title: '第 1 题',
        statePath: ['ASKING'],
        reflexionRounds: 0,
        finalQuestion: 'Redis？',
        followUpAction: 'SWITCH_TOPIC',
        criticApproved: true,
        events: [{
          step: 1,
          questionIndex: 0,
          role: 'interviewer',
          action: 'ask',
          state: 'ASKING',
          headline: 'Interviewer 出题',
          body: 'Redis？',
          approved: null,
          score: null,
          retryHint: null,
          followUpAction: null,
          capability: null,
          evidenceIds: [],
          reflexion: false,
          input: 'instruction',
        }],
      }],
    });
    expect(next).toHaveLength(1);
    expect(next[0].kind).toBe('agent');
    expect(next[0].title).toContain('出题');
  });
});
