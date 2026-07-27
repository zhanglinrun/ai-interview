import { describe, expect, it } from 'vitest';
import { resolveOrchestrationState } from './AgentTracePage';
import type { AgentTraceStep } from '../types/interview';

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
