import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import { interviewApi, type TextSessionMeta } from '../api/interview';
import { getErrorMessage } from '../api/request';
import type {
  AgentPlanProgress,
  AgentTraceGroup,
  AgentTraceStep,
  CandidateMemoryProfile,
} from '../types/interview';
import { EmptyState, LoadingState } from '../components/PageState';
import PageHeader from '../components/ui/PageHeader';
import { formatDate } from '../utils/date';
import { unifiedTraceApi, type UnifiedTrace } from '../api/unifiedTrace';

/** Map raw trace actions to demo-friendly orchestration states. */
export function resolveOrchestrationState(step: AgentTraceStep): string {
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
  if (action === 'finish' || action === 'evaluation_enqueued') {
    return 'READY / EVALUATING';
  }
  return role.toUpperCase() || 'ORCHESTRATOR';
}

function stateBadgeClass(state: string): string {
  if (state === 'PLANNING') return 'bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-200';
  if (state === 'ASKING') return 'bg-violet-100 text-violet-800 dark:bg-violet-900/40 dark:text-violet-200';
  if (state === 'CRITIQUING') return 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200';
  if (state === 'REFLEXION') return 'bg-rose-100 text-rose-800 dark:bg-rose-900/40 dark:text-rose-200';
  return 'bg-stone-100 text-stone-700 dark:bg-stone-800 dark:text-stone-200';
}

function truncate(text: string, max = 280): string {
  const value = (text || '').trim();
  if (value.length <= max) return value;
  return `${value.slice(0, max)}…`;
}

export default function AgentTracePage() {
  const [sessions, setSessions] = useState<TextSessionMeta[]>([]);
  const [selectedId, setSelectedId] = useState<string>('');
  const [groups, setGroups] = useState<AgentTraceGroup[]>([]);
  const [plan, setPlan] = useState<AgentPlanProgress | null>(null);
  const [profile, setProfile] = useState<CandidateMemoryProfile[]>([]);
  const [unified, setUnified] = useState<UnifiedTrace | null>(null);
  const [loadingList, setLoadingList] = useState(true);
  const [loadingTrace, setLoadingTrace] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadSessions = async () => {
    setLoadingList(true);
    setError(null);
    try {
      const list = await interviewApi.listSessions();
      const sorted = [...list].sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''));
      setSessions(sorted);
      setSelectedId(prev => {
        if (prev && sorted.some(s => s.sessionId === prev)) {
          return prev;
        }
        return sorted[0]?.sessionId ?? '';
      });
    } catch (err) {
      setError(getErrorMessage(err, '加载面试会话失败'));
    } finally {
      setLoadingList(false);
    }
  };

  useEffect(() => {
    void loadSessions();
    interviewApi.getCandidateProfile()
      .then(setProfile)
      .catch(() => setProfile([]));
  }, []);

  useEffect(() => {
    if (!selectedId) {
      setGroups([]);
      setPlan(null);
      setUnified(null);
      return;
    }
    setLoadingTrace(true);
    setError(null);
    Promise.all([
      interviewApi.getAgentTrace(selectedId),
      interviewApi.getAgentPlan(selectedId).catch(() => null),
      unifiedTraceApi.timeline(selectedId).catch(() => null),
    ])
      .then(([trace, planProgress, timeline]) => {
        setGroups(trace);
        setPlan(planProgress);
        setUnified(timeline);
      })
      .catch(err => {
        setGroups([]);
        setPlan(null);
        setUnified(null);
        setError(getErrorMessage(err, '加载 Agent Trace 失败（可能非 Agent 模式会话）'));
      })
      .finally(() => setLoadingTrace(false));
  }, [selectedId]);

  return (
    <div className="mx-auto max-w-5xl space-y-5">
      <PageHeader
        eyebrow="Multi-Agent"
        title="Agent 编排 Trace"
        description="回放 PLANNING → ASKING → CRITIQUING ⇄ Reflexion；Critic 打回会带 retryHint 再出题。编排层是状态机，不是自由 AgentLoop。"
      />

      <div className="rounded-lg border border-primary-100 bg-primary-50/60 px-4 py-3 text-sm text-primary-900 dark:border-primary-900 dark:bg-primary-950/30 dark:text-primary-200">
        Demo：先在「岗位实战」或文字模拟面试跑几题，再回本页选会话展开轨迹。记忆叙事可看下方 CandidateMemory 画像摘要。
        {' '}
        <Link to="/job-practice" className="underline underline-offset-2">去开一场面试</Link>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <label className="min-w-[240px] flex-1 space-y-1 text-sm text-stone-600 dark:text-stone-300">
          <span>选择会话</span>
          <select
            className="dark-input w-full px-3 py-2 text-sm"
            value={selectedId}
            onChange={e => setSelectedId(e.target.value)}
          >
            {sessions.length === 0 && <option value="">暂无会话</option>}
            {sessions.map(s => (
              <option key={s.sessionId} value={s.sessionId}>
                {s.sessionId.slice(0, 8)}… · {s.status} · {s.totalQuestions ?? '?'} 题 · {formatDate(s.createdAt)}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          onClick={() => void loadSessions()}
          className="inline-flex items-center gap-2 rounded-lg btn-secondary px-4 py-2 text-sm"
        >
          <RefreshCw className="h-4 w-4" />
          刷新列表
        </button>
      </div>

      {error && (
        <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200">
          {error}
        </div>
      )}

      {plan?.agentMode && plan.plan && (
        <section className="surface-card space-y-2 p-4">
          <h2 className="text-sm font-semibold text-stone-900 dark:text-white">PLANNING 大纲</h2>
          <p className="text-xs text-stone-500">
            进度 {plan.currentIndex}/{plan.plannedTotal} · 难度曲线：{plan.plan.difficultyCurve || '—'}
          </p>
          <ul className="space-y-1 text-sm text-stone-600 dark:text-stone-300">
            {plan.plan.topics.map((t, i) => (
              <li key={i}>
                <span className="font-medium">{t.name}</span>
                {t.focus ? ` — ${t.focus}` : ''}
                {t.questionCount != null ? `（约 ${t.questionCount} 题）` : ''}
              </li>
            ))}
          </ul>
        </section>
      )}

      {unified && (
        <section className="surface-card space-y-2 p-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-sm font-semibold text-stone-900 dark:text-white">统一 Trace 时间线</h2>
            <span className="text-xs text-stone-500">
              Agent {unified.agentRuns.length} · RAG {unified.ragRuns.length} · Tool {unified.toolRuns.length} · LLM {unified.llmUsage.length}
            </span>
          </div>
          <p className="text-xs text-stone-500">
            一场面试包含多个 HTTP trace；此处按 sessionId 聚合，避免把整场面试误认为一次请求。
          </p>
          <ol className="space-y-1 text-xs text-stone-600 dark:text-stone-300">
            {unified.timeline.slice(0, 12).map((event, index) => (
              <li key={`${event.kind}-${event.id}-${index}`} className="flex flex-wrap items-center gap-2 rounded border border-stone-100 px-2 py-1 dark:border-stone-800">
                <span className="font-medium">{event.kind}</span>
                <span>{event.status}</span>
                <span className="text-stone-400">{event.latencyMs ?? 0} ms</span>
                <span className="ml-auto text-stone-400">{event.id}</span>
              </li>
            ))}
          </ol>
        </section>
      )}

      {loadingList || loadingTrace ? (
        <LoadingState className="flex min-h-[30vh] items-center justify-center" />
      ) : groups.length === 0 ? (
        <EmptyState
          title="暂无 Agent Trace"
          description="请选择 Agent 模式会话，或先完成至少一题出题（会写入 planner / interviewer / critic 步骤）。"
        />
      ) : (
        <div className="space-y-4">
          {groups.map((group, gi) => (
            <section key={gi} className="surface-card space-y-3 p-4">
              <h2 className="text-sm font-semibold text-stone-900 dark:text-white">
                {group.questionIndex == null
                  ? 'PLANNING（大纲）'
                  : `第 ${group.questionIndex + 1} 题 · ASKING / CRITIQUING`}
              </h2>
              <ol className="space-y-2">
                {group.steps.map((step, si) => {
                  const state = resolveOrchestrationState(step);
                  const isReject = step.action === 'critique'
                    && /"approved"\s*:\s*false/i.test(step.observation || '');
                  return (
                    <li
                      key={`${step.step}-${si}`}
                      className="rounded-lg border border-stone-100 px-3 py-2 text-sm dark:border-stone-800"
                    >
                      <div className="mb-1 flex flex-wrap items-center gap-2">
                        <span className={`rounded px-2 py-0.5 text-xs font-medium ${stateBadgeClass(state)}`}>
                          {state}
                        </span>
                        <span className="text-xs text-stone-400">
                          #{step.step} · {step.role} · {step.action}
                        </span>
                        {isReject && (
                          <span className="text-xs font-medium text-rose-600 dark:text-rose-300">
                            Critic 打回 → 将带 retryHint 再 ASKING
                          </span>
                        )}
                      </div>
                      {step.actionInput && (
                        <p className="text-xs text-stone-500 dark:text-stone-400">
                          <span className="font-medium">input：</span>{truncate(step.actionInput)}
                        </p>
                      )}
                      {step.observation && (
                        <p className="mt-1 text-xs text-stone-600 dark:text-stone-300">
                          <span className="font-medium">observation：</span>{truncate(step.observation)}
                        </p>
                      )}
                    </li>
                  );
                })}
              </ol>
            </section>
          ))}
        </div>
      )}

      <section className="surface-card space-y-2 p-4">
        <h2 className="text-sm font-semibold text-stone-900 dark:text-white">记忆（CandidateMemory）</h2>
        <p className="text-xs text-stone-500">
          跨场次能力画像；Planner 注入时使用。会话内轮次由 Redis MessageWindowChatMemory 承载。
        </p>
        {profile.length === 0 ? (
          <p className="text-sm text-stone-400">暂无画像条目（完成评估后会沉淀）。</p>
        ) : (
          <ul className="space-y-1 text-sm text-stone-600 dark:text-stone-300">
            {profile.slice(0, 8).map((p, i) => (
              <li key={i}>
                <span className="font-medium">{p.topic || p.capabilityAtomId || '能力'}</span>
                {' · '}
                {p.masteryLevel}
                {p.averageScore != null ? ` · 均分 ${p.averageScore.toFixed(1)}` : ''}
                {p.latestEvidence ? ` · ${truncate(p.latestEvidence, 80)}` : ''}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
