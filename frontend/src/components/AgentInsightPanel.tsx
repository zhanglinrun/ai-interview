import {useCallback, useEffect, useMemo, useState} from 'react';
import {
  Bot,
  Brain,
  ChevronDown,
  ChevronRight,
  Gavel,
  ListChecks,
  Search,
  Sparkles,
  Target,
} from 'lucide-react';
import {interviewApi} from '../api/interview';
import type {AgentPlanProgress, AgentTraceGroup, AgentTraceStep} from '../types/interview';

interface AgentInsightPanelProps {
  sessionId: string;
  /** 每次答题提交后自增，触发轨迹/进度刷新 */
  refreshKey?: number;
  className?: string;
}

const ROLE_META: Record<string, { label: string; badge: string; icon: typeof Bot }> = {
  planner: {label: '规划', badge: 'bg-indigo-100 text-indigo-700 dark:bg-indigo-900/40 dark:text-indigo-300', icon: Brain},
  interviewer: {label: '出题', badge: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300', icon: Bot},
  critic: {label: '审题', badge: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300', icon: Gavel},
  evaluator: {label: '评估', badge: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300', icon: ListChecks},
  orchestrator: {label: '编排', badge: 'bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300', icon: Sparkles},
};

function roleMeta(role: string) {
  return ROLE_META[role?.toLowerCase()] ?? ROLE_META.orchestrator;
}

/**
 * Multi-Agent 决策透明化面板：展示 Planner 大纲、当前进度，以及按题号分组的
 * Planner→Interviewer→Critic→Reflexion 决策轨迹（含工具调用），供演示时肉眼可见的差异化亮点。
 */
export default function AgentInsightPanel({sessionId, refreshKey = 0, className = ''}: AgentInsightPanelProps) {
  const [progress, setProgress] = useState<AgentPlanProgress | null>(null);
  const [trace, setTrace] = useState<AgentTraceGroup[]>([]);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    try {
      const [plan, steps] = await Promise.all([
        interviewApi.getAgentPlan(sessionId),
        interviewApi.getAgentTrace(sessionId),
      ]);
      setProgress(plan);
      setTrace(steps);
    } catch {
      // 决策轨迹是增强信息，加载失败静默降级不打扰面试主流程
    } finally {
      setLoaded(true);
    }
  }, [sessionId]);

  useEffect(() => {
    void load();
  }, [load, refreshKey]);

  const currentTopicIndex = useMemo(() => {
    if (!progress?.plan?.topics?.length) return -1;
    let cursor = 0;
    for (let i = 0; i < progress.plan.topics.length; i++) {
      cursor += Math.max(1, progress.plan.topics[i].questionCount);
      if (progress.currentIndex < cursor) return i;
    }
    return progress.plan.topics.length - 1;
  }, [progress]);

  // 非编排（批量出题）会话不展示本面板
  if (loaded && (!progress || !progress.agentMode)) {
    return null;
  }
  if (!progress) {
    return null;
  }

  const pct = progress.plannedTotal > 0
    ? Math.round((progress.currentIndex / progress.plannedTotal) * 100)
    : 0;

  const groupKey = (g: AgentTraceGroup) => (g.questionIndex === null ? 'plan' : `q${g.questionIndex}`);
  const toggle = (key: string) => setExpanded(prev => ({...prev, [key]: !prev[key]}));

  return (
    <aside className={`surface-card p-5 overflow-y-auto scrollbar-thin ${className}`}>
      <div className="flex items-center gap-2.5 mb-4">
        <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-indigo-500 to-primary-600 flex items-center justify-center shadow-sm">
          <Sparkles className="w-4 h-4 text-white" />
        </div>
        <div>
          <h3 className="text-sm font-semibold text-stone-800 dark:text-stone-100">AI 面试官编排</h3>
          <p className="text-[11px] text-stone-400">规划 Planner · 出题 Interviewer · 审题 Critic</p>
        </div>
      </div>

      {/* 大纲进度 */}
      {progress.plan && (
        <section className="mb-5">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 flex items-center gap-1">
              <Target className="w-3.5 h-3.5" /> 面试大纲
            </span>
            <span className="text-xs text-slate-400">{progress.currentIndex}/{progress.plannedTotal} · {pct}%</span>
          </div>
          {progress.plan.difficultyCurve && (
            <p className="text-xs text-slate-500 dark:text-slate-400 mb-2 italic">
              难度曲线：{progress.plan.difficultyCurve}
            </p>
          )}
          <ol className="space-y-1.5">
            {progress.plan.topics.map((topic, i) => {
              const done = i < currentTopicIndex;
              const active = i === currentTopicIndex;
              return (
                <li
                  key={`${topic.name}-${i}`}
                  className={`flex items-start gap-2 text-xs rounded-lg px-2.5 py-1.5 ${
                    active
                      ? 'bg-primary-50 dark:bg-primary-900/20 ring-1 ring-primary-200 dark:ring-primary-800'
                      : ''
                  }`}
                >
                  <span
                    className={`mt-0.5 w-4 h-4 shrink-0 rounded-full flex items-center justify-center text-[10px] font-bold ${
                      done
                        ? 'bg-emerald-500 text-white'
                        : active
                          ? 'bg-primary-500 text-white'
                          : 'bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-400'
                    }`}
                  >
                    {done ? '✓' : i + 1}
                  </span>
                  <span className="min-w-0">
                    <span className={`font-medium ${active ? 'text-primary-700 dark:text-primary-300' : 'text-slate-700 dark:text-slate-300'}`}>
                      {topic.name}
                    </span>
                    <span className="text-slate-400"> · {topic.questionCount} 题</span>
                    {topic.focus && <span className="block text-slate-400 leading-snug">{topic.focus}</span>}
                  </span>
                </li>
              );
            })}
          </ol>
          {(progress.plan.focusFromResume?.length > 0 || progress.plan.focusFromJd?.length > 0) && (
            <div className="flex flex-wrap gap-1 mt-2">
              {progress.plan.focusFromResume?.map((f, i) => (
                <span key={`r${i}`} className="text-[10px] px-1.5 py-0.5 rounded bg-sky-50 dark:bg-sky-900/30 text-sky-600 dark:text-sky-300">简历·{f}</span>
              ))}
              {progress.plan.focusFromJd?.map((f, i) => (
                <span key={`j${i}`} className="text-[10px] px-1.5 py-0.5 rounded bg-violet-50 dark:bg-violet-900/30 text-violet-600 dark:text-violet-300">JD·{f}</span>
              ))}
            </div>
          )}
        </section>
      )}

      {/* 决策轨迹 */}
      <section>
        <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 flex items-center gap-1 mb-2">
          <Search className="w-3.5 h-3.5" /> 决策轨迹
        </span>
        {trace.length === 0 ? (
          <p className="text-xs text-slate-400">暂无轨迹</p>
        ) : (
          <div className="space-y-1.5">
            {trace.map(group => {
              const key = groupKey(group);
              const open = expanded[key] ?? key === 'plan';
              const title = group.questionIndex === null ? '规划大纲' : `第 ${group.questionIndex + 1} 题`;
              const reflexion = group.steps.filter(s => s.action === 'critique').length;
              return (
                <div key={key} className="border border-slate-100 dark:border-slate-700 rounded-lg">
                  <button
                    type="button"
                    onClick={() => toggle(key)}
                    className="w-full flex items-center justify-between px-2.5 py-1.5 text-xs font-medium text-slate-600 dark:text-slate-300"
                  >
                    <span className="flex items-center gap-1.5">
                      {open ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
                      {title}
                    </span>
                    <span className="text-slate-400">
                      {group.steps.length} 步{reflexion > 1 ? ` · ${reflexion - 1} 次重生成` : ''}
                    </span>
                  </button>
                  {open && (
                    <ul className="px-2.5 pb-2 space-y-1.5">
                      {group.steps.map((step, i) => (
                        <TraceStepRow key={i} step={step} />
                      ))}
                    </ul>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </section>
    </aside>
  );
}

function TraceStepRow({step}: {step: AgentTraceStep}) {
  const meta = roleMeta(step.role);
  const Icon = meta.icon;
  return (
    <li className="text-xs">
      <div className="flex items-center gap-1.5 mb-0.5">
        <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded font-medium ${meta.badge}`}>
          <Icon className="w-3 h-3" />
          {meta.label}
        </span>
        <span className="text-slate-400">{step.action}</span>
      </div>
      {step.observation && (
        <p className="text-slate-500 dark:text-slate-400 leading-snug pl-1 line-clamp-4 whitespace-pre-wrap">
          {step.observation}
        </p>
      )}
    </li>
  );
}
