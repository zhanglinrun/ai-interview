import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { BrainCircuit, CalendarDays, Database, FileStack, Gauge, KeyRound, UserRound } from 'lucide-react';
import { Link } from 'react-router-dom';
import { getStoredUser } from '../api/authStorage';
import { interviewApi } from '../api/interview';
import { reportApi, type LlmUsageItem } from '../api/report';
import PageHeader from '../components/ui/PageHeader';
import type {
  CompressedMemoryTurn,
  InterviewMemory,
  LongTermMemoryItem,
  MemoryMasteryLevel,
  MemoryVerificationState,
} from '../types/interview';
import { getSkillLabel } from '../utils/displayLabels';

const PROFILE_AREAS = [
  ['/history', FileStack, '我的简历', '上传简历，查看解析和修改建议'],
  ['/knowledgebase', Database, '复习资料', '管理面试资料和 RAG 文档'],
  ['/settings', KeyRound, '模型设置', '配置自己的模型访问凭证，查看调用记录'],
  ['/interview-schedule', CalendarDays, '面试日程', '记录已确认的面试安排'],
  ['/eval', BrainCircuit, 'RAG 评测', '检查该不该查资料、资料找没找对、回答靠不靠谱'],
] as const;

const EMPTY_MEMORY: InterviewMemory = {
  shortTerm: {
    sessionId: null,
    skillId: null,
    live: false,
    windowSize: 4,
    agentMessageCount: 0,
    turns: [],
  },
  compressed: { sessionId: null, skillId: null, turns: [] },
  longTerm: [],
};

const MASTERY_META: Record<MemoryMasteryLevel, { label: string; className: string }> = {
  WEAKNESS: { label: '薄弱', className: 'bg-red-50 text-red-700 dark:bg-red-950/40 dark:text-red-300' },
  DEVELOPING: { label: '发展中', className: 'bg-sky-50 text-sky-700 dark:bg-sky-950/40 dark:text-sky-300' },
  STRENGTH: { label: '掌握', className: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300' },
};

const VERIFY_META: Record<MemoryVerificationState, string> = {
  VERIFIED: '已验证',
  PROVISIONAL: '待复测',
};

const FOLLOW_UP_LABELS: Record<string, string> = {
  DEEPEN: '深挖',
  CLARIFY: '澄清',
  REMEDIATE: '补救',
  SWITCH_TOPIC: '换题',
};

function signalLabels(turn: CompressedMemoryTurn): string[] {
  const labels: string[] = [];
  if (turn.hasReasoning) labels.push('有推理');
  if (turn.hasExample) labels.push('有例子');
  if (turn.hasTradeOff) labels.push('有取舍');
  if (turn.expressesUncertainty) labels.push('不确定');
  if (labels.length === 0 && turn.meaningfulChars > 0) labels.push(`${turn.meaningfulChars} 字`);
  return labels;
}

function longTermKey(item: LongTermMemoryItem, index: number): string {
  return item.capabilityAtomId || `${item.topic}-${index}`;
}

export default function ProfilePage() {
  const user = getStoredUser();
  const [memory, setMemory] = useState<InterviewMemory>(EMPTY_MEMORY);
  const [usage, setUsage] = useState<LlmUsageItem[]>([]);

  useEffect(() => {
    let active = true;
    Promise.allSettled([
      interviewApi.getMemory(),
      reportApi.listLlmUsage({ limit: 20 }),
    ]).then(([memoryResult, usageResult]) => {
      if (!active) return;
      if (memoryResult.status === 'fulfilled') setMemory(memoryResult.value);
      if (usageResult.status === 'fulfilled') setUsage(usageResult.value);
    });
    return () => {
      active = false;
    };
  }, []);

  const usageSummary = useMemo(() => usage.reduce((summary, item) => ({
    tokens: summary.tokens + (item.totalTokens ?? 0),
    latencyMs: summary.latencyMs + item.latencyMs,
    degraded: summary.degraded + (item.status === 'DEGRADED' ? 1 : 0),
  }), { tokens: 0, latencyMs: 0, degraded: 0 }), [usage]);

  const shortHint = memory.shortTerm.live
    ? `${getSkillLabel(memory.shortTerm.skillId)} · 近 ${Math.max(1, Math.floor(memory.shortTerm.windowSize / 2))} 轮原文`
    : memory.shortTerm.turns.length > 0
      ? `${getSkillLabel(memory.shortTerm.skillId)} · 最近一场的原文窗口`
      : `进行中的场次保留近 ${Math.max(1, Math.floor(memory.shortTerm.windowSize / 2))} 轮原文`;

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title="我的资料"
        description="在这里管理简历、复习资料、模型设置和面试日程。"
      />

      <div className="surface-card mb-5 flex flex-wrap items-center gap-3 p-4">
        <div className="flex h-11 w-11 items-center justify-center rounded-lg bg-stone-100 text-stone-600 dark:bg-stone-800 dark:text-stone-300">
          <UserRound className="h-5 w-5" />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-stone-900 dark:text-white">
            {user?.displayName || user?.username || '尚未登录'}
          </h2>
          <p className="text-sm text-stone-500 dark:text-stone-400">
            {user ? `@${user.username}` : '登录后可保存简历、代码草稿和面试记录'}
          </p>
        </div>
        {!user && (
          <Link to="/login" className="btn-primary ml-auto px-4 py-2.5 text-sm">登录 / 注册</Link>
        )}
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {PROFILE_AREAS.map(([path, Icon, title, description]) => (
          <Link key={path} to={path} className="surface-card hover-card flex gap-3 p-4">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-stone-100 text-stone-600 dark:bg-stone-800 dark:text-stone-300">
              <Icon className="h-4 w-4" />
            </div>
            <div><h2 className="text-sm font-semibold text-stone-900 dark:text-white">{title}</h2>
            <p className="mt-1 text-sm leading-5 text-stone-500 dark:text-stone-400">{description}</p></div>
          </Link>
        ))}
      </div>

      <section className="surface-card mt-5 p-5">
        <div>
          <h2 className="text-lg font-semibold text-stone-900 dark:text-white">面试记忆</h2>
          <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">
            短期留近几轮原文，压缩只记本场要点，长期跨场记住强弱项。不是一个总分，也不用模型再压一轮摘要。
          </p>
        </div>
        <div className="mt-5 grid gap-4 lg:grid-cols-3">
          <MemoryColumn title="短期记忆" hint={shortHint}>
            {memory.shortTerm.turns.length === 0 ? (
              <EmptyMemory text="还没有进行中的问答。开一场面试后，这里会留下最近一两轮原文。" />
            ) : (
              <ul className="space-y-3">
                {memory.shortTerm.turns.map((turn, index) => (
                  <li key={`${turn.role}-${index}`}>
                    <p className="text-xs text-stone-400">{turn.role === 'USER' ? '你' : '面试官'}</p>
                    <p className="mt-1 text-sm leading-6 text-stone-700 dark:text-stone-200">{turn.text}</p>
                  </li>
                ))}
              </ul>
            )}
          </MemoryColumn>

          <MemoryColumn
            title="压缩记忆"
            hint={memory.compressed.turns.length > 0
              ? `${getSkillLabel(memory.compressed.skillId)} · ${memory.compressed.turns.length} 道主问题`
              : '本场已答主问题的主题、信号和跟进'}
          >
            {memory.compressed.turns.length === 0 ? (
              <EmptyMemory text="答完主问题后，这里只留下主题和结构信号，不保存整段对话。" />
            ) : (
              <ul className="space-y-3">
                {memory.compressed.turns.map((turn) => (
                  <li key={turn.questionIndex}>
                    <div className="flex items-start justify-between gap-2">
                      <p className="text-sm font-medium text-stone-900 dark:text-white">{turn.topic}</p>
                      {turn.followUpAction && (
                        <span className="shrink-0 text-xs text-stone-400">
                          {FOLLOW_UP_LABELS[turn.followUpAction] ?? turn.followUpAction}
                        </span>
                      )}
                    </div>
                    {signalLabels(turn).length > 0 && (
                      <p className="mt-1 text-xs text-stone-400">{signalLabels(turn).join(' · ')}</p>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </MemoryColumn>

          <MemoryColumn title="长期记忆" hint="评估分跨场聚合，至少 2 场 3 次观测才标已验证">
            {memory.longTerm.length === 0 ? (
              <EmptyMemory text="完成评估后，强弱项会留在这里，下场 Planner 会读到。" />
            ) : (
              <ul className="space-y-3">
                {memory.longTerm.map((item, index) => (
                  <li key={longTermKey(item, index)} className="rounded-xl border border-stone-200 p-3 dark:border-stone-800">
                    <div className="flex items-center justify-between gap-2">
                      <h3 className="min-w-0 text-sm font-medium text-stone-900 dark:text-white">{item.topic}</h3>
                      <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${MASTERY_META[item.masteryLevel].className}`}>
                        {MASTERY_META[item.masteryLevel].label}
                      </span>
                    </div>
                    <p className="mt-1 text-xs text-stone-400">
                      {VERIFY_META[item.verificationState]}
                      {item.averageScore != null ? ` · 均分 ${item.averageScore}` : ''}
                      {` · ${item.sessionCount} 场 / ${item.observationCount} 次`}
                    </p>
                    {item.latestEvidence && (
                      <p className="mt-2 text-xs leading-5 text-stone-500 dark:text-stone-400">{item.latestEvidence}</p>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </MemoryColumn>
        </div>
      </section>

      <section className="surface-card mt-5 p-5">
        <div className="flex items-center gap-2">
          <Gauge className="h-5 w-5 text-primary-600" />
          <h2 className="text-lg font-semibold text-stone-900 dark:text-white">最近模型用量</h2>
        </div>
        {usage.length === 0 ? (
          <p className="mt-4 text-sm leading-6 text-stone-400">暂无用量记录。未记录不等于消耗为 0。</p>
        ) : (
          <div className="mt-5 grid gap-4 sm:grid-cols-3">
            <div>
              <p className="text-xs text-stone-400">最近 {usage.length} 次调用 Token</p>
              <p className="mt-1 text-2xl font-semibold text-stone-900 dark:text-white">{usageSummary.tokens.toLocaleString()}</p>
            </div>
            <div>
              <p className="text-xs text-stone-400">累计模型耗时</p>
              <p className="mt-1 text-lg font-semibold text-stone-900 dark:text-white">{(usageSummary.latencyMs / 1000).toFixed(1)} 秒</p>
            </div>
            <div>
              <p className="text-xs text-stone-400">明确降级</p>
              <p className="mt-1 text-lg font-semibold text-stone-900 dark:text-white">{usageSummary.degraded} 次</p>
            </div>
            <p className="text-xs leading-5 text-stone-400 sm:col-span-3">费用只在模型服务返回或已配置价格时展示。</p>
          </div>
        )}
      </section>
    </div>
  );
}

function MemoryColumn({
  title,
  hint,
  children,
}: {
  title: string;
  hint: string;
  children: ReactNode;
}) {
  return (
    <div className="rounded-xl border border-stone-200 p-4 dark:border-stone-800">
      <h3 className="text-sm font-semibold text-stone-900 dark:text-white">{title}</h3>
      <p className="mt-1 text-xs leading-5 text-stone-400">{hint}</p>
      <div className="mt-4">{children}</div>
    </div>
  );
}

function EmptyMemory({ text }: { text: string }) {
  return (
    <p className="text-sm leading-6 text-stone-500 dark:text-stone-400">{text}</p>
  );
}
