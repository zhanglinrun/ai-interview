import { useEffect, useMemo, useState } from 'react';
import { BrainCircuit, CalendarDays, Database, FileStack, Gauge, KeyRound, UserRound } from 'lucide-react';
import { Link } from 'react-router-dom';
import { getStoredUser } from '../api/authStorage';
import { reportApi, type CapabilityProfileItem, type LlmUsageItem } from '../api/report';
import PageHeader from '../components/ui/PageHeader';
import { getCapabilityDisplayName } from '../utils/displayLabels';

const PROFILE_AREAS = [
  ['/history', FileStack, '我的简历', '上传简历，查看解析和修改建议'],
  ['/knowledgebase', Database, '复习资料', '管理面试资料和 RAG 文档'],
  ['/settings', KeyRound, '模型设置', '配置自己的模型 Key，查看调用记录'],
  ['/interview-schedule', CalendarDays, '面试日程', '记录已确认的面试安排'],
  ['/eval', BrainCircuit, 'RAG 评测', '检查检索和回答效果'],
] as const;

export default function ProfilePage() {
  const user = getStoredUser();
  const [profile, setProfile] = useState<CapabilityProfileItem[]>([]);
  const [usage, setUsage] = useState<LlmUsageItem[]>([]);

  useEffect(() => {
    let active = true;
    Promise.allSettled([
      reportApi.listCapabilityProfile(),
      reportApi.listLlmUsage({ limit: 20 }),
    ]).then(([profileResult, usageResult]) => {
      if (!active) return;
      if (profileResult.status === 'fulfilled') setProfile(profileResult.value);
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

  const stateMeta: Record<CapabilityProfileItem['state'], { label: string; className: string }> = {
    UNVERIFIED: { label: '待验证', className: 'bg-stone-100 text-stone-600 dark:bg-stone-800 dark:text-stone-300' },
    WEAK: { label: '薄弱', className: 'bg-red-50 text-red-700 dark:bg-red-950/40 dark:text-red-300' },
    STABLE: { label: '稳定', className: 'bg-sky-50 text-sky-700 dark:bg-sky-950/40 dark:text-sky-300' },
    STRENGTH: { label: '优势', className: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300' },
    REVIEW: { label: '需复核', className: 'bg-amber-50 text-amber-700 dark:bg-amber-950/40 dark:text-amber-300' },
  };

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
            {user ? `@${user.username}` : '登录后可保存岗位、代码草稿和面试记录'}
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

      <section className="mt-5 grid gap-5 lg:grid-cols-[1.3fr_0.7fr]">
        <div className="surface-card p-5">
          <div className="flex items-start justify-between gap-3">
            <div>
              <h2 className="text-lg font-semibold text-stone-900 dark:text-white">能力画像</h2>
              <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">根据已完成的面试和训练更新，不用一个总分概括全部能力。</p>
            </div>
            <Link to="/training" className="text-sm font-medium text-primary-700 dark:text-primary-300">去练习</Link>
          </div>
          {profile.length === 0 ? (
            <div className="mt-4 rounded-lg bg-stone-100 p-4 text-sm leading-6 text-stone-500 dark:bg-stone-800 dark:text-stone-400">还没有能力记录。完成一次岗位面试后，这里会显示你的优势和待练习项。</div>
          ) : (
            <div className="mt-5 grid gap-3 sm:grid-cols-2">
              {profile.map((item) => (
                <div key={item.capabilityAtomId} className="rounded-xl border border-stone-200 p-4 dark:border-stone-800">
                  <div className="flex items-center justify-between gap-2"><h3 className="min-w-0 text-sm font-medium text-stone-900 dark:text-white">{getCapabilityDisplayName(item.capabilityAtomId, item.capabilityName)}</h3><span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${stateMeta[item.state].className}`}>{stateMeta[item.state].label}</span></div>
                  <p className="mt-2 text-xs text-stone-400">{item.evidenceCount} 次有效记录{item.reviewRequired ? ' · 最近表现不一致' : ''}</p>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="surface-card p-5">
          <div className="flex items-center gap-2"><Gauge className="h-5 w-5 text-primary-600" /><h2 className="text-lg font-semibold text-stone-900 dark:text-white">最近模型用量</h2></div>
          {usage.length === 0 ? <p className="mt-4 text-sm leading-6 text-stone-400">暂无用量记录。未记录不等于消耗为 0。</p> : (
            <div className="mt-5 space-y-4">
              <div><p className="text-xs text-stone-400">最近 {usage.length} 次调用 Token</p><p className="mt-1 text-2xl font-semibold text-stone-900 dark:text-white">{usageSummary.tokens.toLocaleString()}</p></div>
              <div><p className="text-xs text-stone-400">累计模型耗时</p><p className="mt-1 text-lg font-semibold text-stone-900 dark:text-white">{(usageSummary.latencyMs / 1000).toFixed(1)} 秒</p></div>
              <div><p className="text-xs text-stone-400">明确降级</p><p className="mt-1 text-lg font-semibold text-stone-900 dark:text-white">{usageSummary.degraded} 次</p></div>
              <p className="text-xs leading-5 text-stone-400">费用只在模型服务返回或已配置价格时展示。</p>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
