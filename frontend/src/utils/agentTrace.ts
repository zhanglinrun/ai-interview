import type { AgentTraceEvent } from '../types/interview';

/** Map raw trace actions to demo-friendly orchestration states. */
export function resolveOrchestrationState(step: {
  action?: string;
  role?: string;
  actionInput?: string;
}): string {
  const action = (step.action || '').toLowerCase();
  const role = (step.role || '').toLowerCase();
  if (action === 'state' && step.actionInput) {
    return step.actionInput;
  }
  if (action === 'plan' || action === 'plan_fallback' || role === 'planner') {
    return 'PLANNING';
  }
  if (action === 'critique') {
    return 'CRITIQUING';
  }
  if (action === 'reflexion_limit' || action.includes('reflexion')) {
    return 'REFLEXION';
  }
  if (action === 'ask' && (step.actionInput || '').toLowerCase().includes('retryhint')) {
    return 'REFLEXION';
  }
  if (action === 'ask' || action === 'ask_failed' || role === 'interviewer') {
    return 'ASKING';
  }
  if (action === 'finish' || action === 'evaluation_enqueued' || action === 'enqueue_evaluation'
      || action === 'evaluate_completed' || action === 'evaluate_failed' || role === 'evaluator') {
    return action === 'evaluate_failed' ? 'EVALUATING' : 'READY / EVALUATING';
  }
  return role.toUpperCase() || 'ORCHESTRATOR';
}

export function stateBadgeClass(state: string): string {
  if (state === 'PLANNING') return 'bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-200';
  if (state === 'ASKING') return 'bg-violet-100 text-violet-800 dark:bg-violet-900/40 dark:text-violet-200';
  if (state === 'CRITIQUING') return 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200';
  if (state === 'REFLEXION') return 'bg-rose-100 text-rose-800 dark:bg-rose-900/40 dark:text-rose-200';
  if (state === 'EVALUATING' || state === 'READY' || state === 'READY / EVALUATING') {
    return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200';
  }
  return 'bg-stone-100 text-stone-700 dark:bg-stone-800 dark:text-stone-200';
}

export function eventToneClass(event: AgentTraceEvent): string {
  if (event.reflexion || event.approved === false) {
    return 'border-rose-200 dark:border-rose-900';
  }
  if (event.approved === true) {
    return 'border-emerald-200 dark:border-emerald-900';
  }
  return 'border-stone-100 dark:border-stone-800';
}
