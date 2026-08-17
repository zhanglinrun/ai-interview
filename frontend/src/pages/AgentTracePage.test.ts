import { describe, expect, it } from 'vitest';
import { eventToneClass, resolveOrchestrationState, stateBadgeClass } from '../utils/agentTrace';
import type { AgentTraceEvent, AgentTraceStep } from '../types/interview';

function step(partial: Partial<AgentTraceStep>): AgentTraceStep {
  return {
    step: 1,
    role: 'orchestrator',
    action: 'turn_decision',
    actionInput: '',
    observation: '',
    ...partial,
  };
}

function event(partial: Partial<AgentTraceEvent>): AgentTraceEvent {
  return {
    step: 1,
    questionIndex: 0,
    role: 'critic',
    action: 'critique',
    state: 'CRITIQUING',
    headline: 'Critic 打回',
    body: '题面太宽',
    approved: false,
    score: 40,
    retryHint: '收窄',
    followUpAction: null,
    capability: null,
    evidenceIds: [],
    reflexion: true,
    ...partial,
  };
}

describe('resolveOrchestrationState', () => {
  it('maps planner / ask / critique / reflexion', () => {
    expect(resolveOrchestrationState(step({ role: 'planner', action: 'plan' }))).toBe('PLANNING');
    expect(resolveOrchestrationState(step({ role: 'interviewer', action: 'ask' }))).toBe('ASKING');
    expect(resolveOrchestrationState(step({ role: 'critic', action: 'critique' }))).toBe('CRITIQUING');
    expect(resolveOrchestrationState(step({
      role: 'interviewer',
      action: 'ask',
      actionInput: 'retryHint: 请更具体',
    }))).toBe('REFLEXION');
    expect(resolveOrchestrationState(step({ action: 'reflexion_limit' }))).toBe('REFLEXION');
    expect(resolveOrchestrationState(step({
      role: 'orchestrator',
      action: 'state',
      actionInput: 'REFLEXION',
    }))).toBe('REFLEXION');
    expect(resolveOrchestrationState(step({
      role: 'orchestrator',
      action: 'state',
      actionInput: 'ASKING',
    }))).toBe('ASKING');
  });
});

describe('agent trace display', () => {
  it('marks reflexion events', () => {
    expect(eventToneClass(event({}))).toContain('rose');
    expect(eventToneClass(event({ reflexion: false, approved: true, state: 'CRITIQUING' }))).toContain('emerald');
    expect(stateBadgeClass('REFLEXION')).toContain('rose');
  });
});
