import { useEffect, useMemo, useState, type ComponentType } from 'react';
import {
  ArrowRight,
  CalendarDays,
  CheckCircle2,
  Circle,
  Database,
  FileStack,
  History,
  PlayCircle,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import { historyApi, type ResumeListItem } from '../api/history';
import { interviewApi, type TextSessionMeta } from '../api/interview';
import { interviewScheduleApi } from '../api/interviewSchedule';
import { knowledgeBaseApi } from '../api/knowledgebase';
import type { LongTermMemoryItem } from '../types/interview';
import InterviewStatusBadge from '../components/InterviewStatusBadge';
import { LoadingState } from '../components/PageState';
import PageHeader from '../components/ui/PageHeader';
import { useAuthStore } from '../stores/authStore';
import type { InterviewSchedule } from '../types/interviewSchedule';
import { compareDateAsc, compareDateDesc, formatDateTime, formatTimeAgo } from '../utils/date';
import { getSkillLabel } from '../utils/displayLabels';
import { getInterviewViewPath } from '../utils/interviewNavigation';
import { hasReliableEvaluationScore, isEvaluationCompleted } from '../utils/interviewStatus';

const FLOW_STEPS = [
  ['1', '准备材料', '贴 JD，选简历和知识库。'],
  ['2', '生成大纲', 'Planner 按材料定本场主题。'],
  ['3', '边答边追问', '按回答决定深挖或换题。'],
  ['4', '查看复盘', '回看表现，再继续或重开。'],
] as const;

export { getSkillLabel };

const SCHEDULE_TYPE_LABELS: Record<string, string> = {
  ONSITE: '现场',
  VIDEO: '视频',
  PHONE: '电话',
};

export interface DashboardNextStep {
  title: string;
  description: string;
  primary: { to: string; label: string; state?: { sessionIdToResume: string } };
  secondary?: { to: string; label: string };
}

export function canContinueSession(session: Pick<TextSessionMeta, 'status' | 'evaluateStatus'>): boolean {
  return !['COMPLETED', 'EVALUATED', 'COMPLETING', 'ABORTED', 'FAILED'].includes(session.status)
    && !isEvaluationCompleted(session.evaluateStatus, session.status);
}

export function pickUpcomingSchedules(
  items: InterviewSchedule[],
  nowMs = Date.now(),
  limit = 3,
): InterviewSchedule[] {
  return items
    .filter((item) => item.status === 'PENDING' && new Date(item.interviewTime).getTime() >= nowMs)
    .sort((left, right) => compareDateAsc(left.interviewTime, right.interviewTime))
    .slice(0, limit);
}

export function pickWeakLongTermMemories(
  items: LongTermMemoryItem[],
  limit = 3,
): LongTermMemoryItem[] {
  return items
    .filter((item) => item.masteryLevel === 'WEAKNESS')
    .sort((left, right) => {
      const rank = (state: LongTermMemoryItem['verificationState']) => (state === 'VERIFIED' ? 0 : 1);
      return rank(left.verificationState) - rank(right.verificationState);
    })
    .slice(0, limit);
}

export function buildDashboardNextStep(input: {
  sessions: TextSessionMeta[];
  resumeCount: number;
  knowledgeReadyCount: number;
  upcoming: InterviewSchedule[];
  weakMemories: LongTermMemoryItem[];
}): DashboardNextStep {
  const unfinished = input.sessions.find(canContinueSession);
  if (unfinished) {
    const started = formatTimeAgo(unfinished.createdAt);
    const questionHint = unfinished.totalQuestions > 0 ? ` · 共 ${unfinished.totalQuestions} 题` : '';
    return {
      title: `继续未完成的 ${getSkillLabel(unfinished.skillId)} 面试`,
      description: `${started}开始${questionHint}。从上次停下的地方继续，或新开一场按 JD 出题。`,
      primary: {
        to: '/interview',
        label: '继续这场面试',
        state: { sessionIdToResume: unfinished.sessionId },
      },
      secondary: { to: '/interview', label: '新开一场' },
    };
  }

  const nextSchedule = input.upcoming[0];
  if (nextSchedule) {
    return {
      title: `下场是 ${nextSchedule.companyName} · ${nextSchedule.position}`,
      description: `${formatDateTime(nextSchedule.interviewTime)}。先按 JD 和简历开一场文字模拟。`,
      primary: { to: '/interview', label: '开始模拟面试' },
      secondary: { to: '/interview-schedule', label: '查看日程' },
    };
  }

  if (input.resumeCount === 0) {
    return {
      title: '先把简历备上',
      description: '没有简历也能贴 JD 开场，但选了简历后，出题会贴着你的项目经历。',
      primary: { to: '/upload', label: '上传简历' },
      secondary: { to: '/interview', label: '先用 JD 开场' },
    };
  }

  const weak = input.weakMemories[0];
  if (weak) {
    return {
      title: `针对「${weak.topic}」再练一场`,
      description: '长期记忆来自已完成面试的评估分，不是一个总分。薄弱项适合带着 JD 再开一场。',
      primary: { to: '/interview', label: '开一场针对性练习' },
      secondary: { to: '/profile', label: '看三层记忆' },
    };
  }

  if (input.sessions.length === 0) {
    const knowledgeHint = input.knowledgeReadyCount > 0
      ? `已有 ${input.knowledgeReadyCount} 份可用知识库。`
      : '知识库可选，用来按资料出题。';
    return {
      title: '开第一场模拟面试',
      description: `已有 ${input.resumeCount} 份简历。${knowledgeHint}贴上 JD 后即可开始。`,
      primary: { to: '/interview', label: '开始一场面试' },
    };
  }

  return {
    title: '再开一场按材料出题的面试',
    description: 'Planner 会按 JD / 简历 / 知识库定本场主题，而不是通用开场。',
    primary: { to: '/interview', label: '开始一场面试' },
    secondary: { to: '/interviews', label: '查看面试记录' },
  };
}

const LOADING_STEP: DashboardNextStep = {
  title: '准备下一场面试',
  description: '正在查看你的简历、面试记录和日程。',
  primary: { to: '/interview', label: '开始一场面试' },
};

export default function DashboardPage() {
  const user = useAuthStore();
  const [sessions, setSessions] = useState<TextSessionMeta[]>([]);
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [knowledgeReadyCount, setKnowledgeReadyCount] = useState(0);
  const [knowledgeProcessingCount, setKnowledgeProcessingCount] = useState(0);
  const [schedules, setSchedules] = useState<InterviewSchedule[]>([]);
  const [longTerm, setLongTerm] = useState<LongTermMemoryItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [warning, setWarning] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    Promise.allSettled([
      interviewApi.listSessions(),
      historyApi.getResumes(),
      knowledgeBaseApi.getStatistics(),
      interviewScheduleApi.getAll({ status: 'PENDING' }),
      interviewApi.getMemory(),
    ]).then(([sessionResult, resumeResult, knowledgeResult, scheduleResult, memoryResult]) => {
      if (!active) return;

      const failed: string[] = [];
      if (sessionResult.status === 'fulfilled') {
        setSessions(sessionResult.value);
      } else {
        failed.push('面试记录');
      }
      if (resumeResult.status === 'fulfilled') {
        setResumes(resumeResult.value);
      } else {
        failed.push('简历');
      }
      if (knowledgeResult.status === 'fulfilled') {
        setKnowledgeReadyCount(knowledgeResult.value.completedCount);
        setKnowledgeProcessingCount(knowledgeResult.value.processingCount);
      } else {
        failed.push('知识库');
      }
      if (scheduleResult.status === 'fulfilled') {
        setSchedules(scheduleResult.value);
      } else {
        failed.push('面试日程');
      }
      if (memoryResult.status === 'fulfilled') {
        setLongTerm(memoryResult.value.longTerm);
      } else {
        failed.push('面试记忆');
      }
      setWarning(failed.length > 0 ? `部分数据暂未加载：${failed.join('、')}` : '');
    }).finally(() => {
      if (active) setLoading(false);
    });

    return () => {
      active = false;
    };
  }, []);

  const unfinishedSessions = useMemo(() => sessions.filter(canContinueSession), [sessions]);
  const recentSessions = useMemo(
    () => [...sessions].sort((left, right) => compareDateDesc(left.createdAt, right.createdAt)).slice(0, 5),
    [sessions],
  );
  const upcoming = useMemo(() => pickUpcomingSchedules(schedules), [schedules]);
  const weakMemories = useMemo(() => pickWeakLongTermMemories(longTerm), [longTerm]);
  const nextStep = useMemo(
    () => (loading
      ? LOADING_STEP
      : buildDashboardNextStep({
        sessions,
        resumeCount: resumes.length,
        knowledgeReadyCount,
        upcoming,
        weakMemories,
      })),
    [loading, sessions, resumes.length, knowledgeReadyCount, upcoming, weakMemories],
  );

  const displayName = user?.displayName || user?.username;
  const latestResume = [...resumes].sort((left, right) => compareDateDesc(left.uploadedAt, right.uploadedAt))[0];

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        eyebrow={displayName ? `${displayName} 的工作台` : '工作台'}
        title={nextStep.title}
        description={nextStep.description}
        action={(
          <div className="flex flex-wrap gap-2">
            <Link
              to={nextStep.primary.to}
              state={nextStep.primary.state}
              className="btn-primary inline-flex items-center gap-2 px-4 py-2.5 text-sm"
            >
              {nextStep.primary.label}
              <ArrowRight className="h-4 w-4" />
            </Link>
            {nextStep.secondary && (
              <Link
                to={nextStep.secondary.to}
                className="btn-secondary inline-flex items-center px-4 py-2.5 text-sm"
              >
                {nextStep.secondary.label}
              </Link>
            )}
          </div>
        )}
      />

      {warning && (
        <div className="mb-6 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-300">
          {warning}
        </div>
      )}

      {loading ? (
        <LoadingState label="正在整理工作台" compact />
      ) : (
        <>
          <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <StatLink
              to="/interviews"
              icon={History}
              label="面试记录"
              value={sessions.length}
              suffix="场"
              hint={unfinishedSessions.length > 0 ? `${unfinishedSessions.length} 场未完成` : '没有进行中的场次'}
            />
            <StatLink
              to={resumes.length > 0 ? '/history' : '/upload'}
              icon={FileStack}
              label="简历"
              value={resumes.length}
              suffix="份"
              hint={latestResume?.filename ?? '还没有简历'}
            />
            <StatLink
              to="/knowledgebase"
              icon={Database}
              label="知识库"
              value={knowledgeReadyCount}
              suffix="份可用"
              hint={knowledgeProcessingCount > 0 ? `${knowledgeProcessingCount} 份处理中` : '可按资料出题'}
            />
            <StatLink
              to="/interview-schedule"
              icon={CalendarDays}
              label="近期日程"
              value={upcoming.length}
              suffix="场"
              hint={upcoming[0] ? `${upcoming[0].companyName} · ${upcoming[0].position}` : '还没有临近面试'}
            />
          </section>

          <section className="mt-5 grid gap-5 lg:grid-cols-[minmax(0,1.7fr)_minmax(18rem,1fr)]">
            <div className="surface-card p-5">
              <div className="mb-4 flex items-center justify-between gap-3">
                <h2 className="text-base text-stone-900 dark:text-white">最近面试</h2>
                <Link to="/interviews" className="text-sm text-primary-700 hover:text-primary-800 dark:text-primary-300">
                  全部记录
                </Link>
              </div>
              {recentSessions.length === 0 ? (
                <p className="text-sm leading-6 text-stone-500 dark:text-stone-400">
                  还没有面试记录。贴上 JD 或选好简历后就可以开第一场。
                </p>
              ) : (
                <ul className="divide-y divide-stone-100 dark:divide-stone-800">
                  {recentSessions.map((session) => (
                    <RecentSessionRow key={session.sessionId} session={session} />
                  ))}
                </ul>
              )}
            </div>

            <aside className="space-y-5">
              <div className="surface-card p-5">
                <h2 className="text-base text-stone-900 dark:text-white">材料准备</h2>
                <div className="mt-3 space-y-1">
                  <MaterialRow
                    ready={resumes.length > 0}
                    title="简历"
                    detail={resumes.length > 0 ? `${resumes.length} 份已上传` : '还没有简历，出题会少一段经历'}
                    to={resumes.length > 0 ? '/history' : '/upload'}
                    action={resumes.length > 0 ? '管理' : '上传'}
                  />
                  <MaterialRow
                    ready={knowledgeReadyCount > 0}
                    title="知识库"
                    detail={knowledgeReadyCount > 0 ? `${knowledgeReadyCount} 份可用` : '可选。有资料时可以按文档出题'}
                    to={knowledgeReadyCount > 0 ? '/knowledgebase' : '/knowledgebase/upload'}
                    action={knowledgeReadyCount > 0 ? '管理' : '上传'}
                  />
                  <MaterialRow
                    ready={unfinishedSessions.length === 0}
                    title="未完成场次"
                    detail={unfinishedSessions.length > 0 ? `${unfinishedSessions.length} 场可以继续` : '没有进行中的场次'}
                    to={unfinishedSessions[0]
                      ? { pathname: '/interview', state: { sessionIdToResume: unfinishedSessions[0].sessionId } }
                      : '/interviews'}
                    action={unfinishedSessions.length > 0 ? '继续' : '记录'}
                    warn={unfinishedSessions.length > 0}
                  />
                </div>
              </div>

              {upcoming.length > 0 && (
                <div className="surface-card p-5">
                  <div className="mb-3 flex items-center justify-between gap-3">
                    <h2 className="text-base text-stone-900 dark:text-white">临近面试</h2>
                    <Link to="/interview-schedule" className="text-sm text-primary-700 hover:text-primary-800 dark:text-primary-300">
                      日程
                    </Link>
                  </div>
                  <ul className="space-y-3">
                    {upcoming.map((item) => (
                      <li key={item.id}>
                        <Link to="/interview-schedule" className="block rounded-lg p-1 -mx-1 hover:bg-stone-50 dark:hover:bg-stone-800/60">
                          <p className="text-sm font-medium text-stone-900 dark:text-white">
                            {item.companyName}
                            <span className="font-normal text-stone-500"> · {item.position}</span>
                          </p>
                          <p className="mt-1 text-xs text-stone-500 dark:text-stone-400">
                            {formatDateTime(item.interviewTime)}
                            {' · '}
                            {SCHEDULE_TYPE_LABELS[item.interviewType] ?? item.interviewType}
                            {item.roundNumber > 0 ? ` · 第 ${item.roundNumber} 轮` : ''}
                          </p>
                        </Link>
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              {weakMemories.length > 0 && (
                <div className="surface-card p-5">
                  <div className="mb-3 flex items-center justify-between gap-3">
                    <h2 className="text-base text-stone-900 dark:text-white">该练的点</h2>
                    <Link to="/profile" className="text-sm text-primary-700 hover:text-primary-800 dark:text-primary-300">
                      记忆
                    </Link>
                  </div>
                  <ul className="space-y-2">
                    {weakMemories.map((item) => (
                      <li key={item.capabilityAtomId ?? item.topic} className="flex items-center justify-between gap-2">
                        <span className="min-w-0 truncate text-sm text-stone-800 dark:text-stone-100">
                          {item.topic}
                        </span>
                        <span className="shrink-0 rounded-full bg-red-50 px-2 py-0.5 text-xs font-medium text-red-700 dark:bg-red-950/40 dark:text-red-300">
                          {item.verificationState === 'VERIFIED' ? '已验证薄弱' : '薄弱'}
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </aside>
          </section>

          <section className="surface-card mt-5 p-4">
            <p className="mb-3 text-xs font-medium text-stone-400 dark:text-stone-500">一场面试怎么走</p>
            <div className="grid gap-3 sm:grid-cols-4">
              {FLOW_STEPS.map(([number, title, description]) => (
                <div key={number} className="flex gap-2.5">
                  <div className="flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-primary-50 text-xs font-semibold text-primary-700 dark:bg-primary-950/50 dark:text-primary-300">
                    {number}
                  </div>
                  <div>
                    <h3 className="text-sm font-semibold text-stone-900 dark:text-white">{title}</h3>
                    <p className="mt-0.5 text-xs leading-5 text-stone-500 dark:text-stone-400">{description}</p>
                  </div>
                </div>
              ))}
            </div>
          </section>
        </>
      )}
    </div>
  );
}

function StatLink({
  to,
  icon: Icon,
  label,
  value,
  suffix,
  hint,
}: {
  to: string;
  icon: ComponentType<{ className?: string }>;
  label: string;
  value: number;
  suffix: string;
  hint: string;
}) {
  return (
    <Link to={to} className="surface-card hover-card flex items-center gap-3 p-4">
      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-stone-100 text-stone-600 dark:bg-stone-800 dark:text-stone-300">
        <Icon className="h-5 w-5" />
      </div>
      <div className="min-w-0">
        <p className="text-xs text-stone-500 dark:text-stone-400">{label}</p>
        <p className="mt-0.5 text-xl font-semibold text-stone-900 dark:text-white">
          {value}
          <span className="ml-1.5 text-xs font-normal text-stone-400">{suffix}</span>
        </p>
        <p className="mt-0.5 truncate text-xs text-stone-400">{hint}</p>
      </div>
    </Link>
  );
}

function MaterialRow({
  ready,
  title,
  detail,
  to,
  action,
  warn = false,
}: {
  ready: boolean;
  title: string;
  detail: string;
  to: string | { pathname: string; state?: { sessionIdToResume: string } };
  action: string;
  warn?: boolean;
}) {
  const className = 'flex items-start gap-3 rounded-lg px-1 py-2.5 transition-colors hover:bg-stone-50 dark:hover:bg-stone-800/60';
  const body = (
    <>
      {ready ? (
        <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600 dark:text-emerald-400" />
      ) : (
        <Circle className={`mt-0.5 h-4 w-4 shrink-0 ${warn ? 'text-amber-500' : 'text-stone-300 dark:text-stone-600'}`} />
      )}
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-stone-900 dark:text-white">{title}</p>
        <p className="mt-0.5 text-xs leading-5 text-stone-500 dark:text-stone-400">{detail}</p>
      </div>
      <span className="shrink-0 pt-0.5 text-xs text-stone-400">{action}</span>
    </>
  );

  if (typeof to === 'string') {
    return <Link to={to} className={className}>{body}</Link>;
  }
  return <Link to={to.pathname} state={to.state} className={className}>{body}</Link>;
}

function RecentSessionRow({ session }: { session: TextSessionMeta }) {
  const continuable = canContinueSession(session);
  const destination = continuable
    ? { pathname: '/interview', state: { sessionIdToResume: session.sessionId } }
    : { pathname: getInterviewViewPath(session.sessionId) };

  return (
    <li>
      <Link
        to={destination.pathname}
        state={destination.state}
        className="flex items-center gap-3 py-3 first:pt-0 last:pb-0 hover:bg-stone-50 dark:hover:bg-stone-800/40 -mx-2 px-2 rounded-lg"
      >
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <p className="text-sm font-medium text-stone-900 dark:text-white">
              {getSkillLabel(session.skillId)}
            </p>
            <InterviewStatusBadge
              status={session.status}
              evaluateStatus={session.evaluateStatus}
              className="flex items-center gap-1"
              iconClassName="h-3.5 w-3.5"
              textClassName="text-xs text-stone-500 dark:text-stone-400"
            />
          </div>
          <p className="mt-1 text-xs text-stone-400">
            {session.totalQuestions > 0 ? `${session.totalQuestions} 题 · ` : ''}
            {formatTimeAgo(session.createdAt)}
            {hasReliableEvaluationScore({
              evaluateStatus: session.evaluateStatus,
              status: session.status,
              overallScore: session.overallScore,
              evaluationDegraded: session.evaluationDegraded,
            }) ? ` · ${session.overallScore} 分` : ''}
          </p>
        </div>
        <span className="inline-flex shrink-0 items-center gap-1 text-xs font-medium text-primary-700 dark:text-primary-300">
          {continuable ? <PlayCircle className="h-3.5 w-3.5" /> : null}
          {continuable ? '继续' : '复盘'}
        </span>
      </Link>
    </li>
  );
}
