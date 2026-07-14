import {useCallback, useEffect, useState} from 'react';
import {BadgeCheck, CircleDashed, TrendingDown, TrendingUp} from 'lucide-react';
import {interviewApi} from '../api/interview';
import type {CandidateMemoryProfile} from '../types/interview';
import {LoadingState} from './PageState';

interface CandidateMemoryPanelProps {
  skillId?: string;
  className?: string;
}

export default function CandidateMemoryPanel({skillId, className = ''}: CandidateMemoryPanelProps) {
  const [profiles, setProfiles] = useState<CandidateMemoryProfile[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await interviewApi.getCandidateProfile(skillId);
      setProfiles(data);
    } catch {
      setProfiles([]);
    } finally {
      setLoading(false);
    }
  }, [skillId]);

  useEffect(() => {
    void load();
  }, [load]);

  if (loading) {
    return (
      <div className={`surface-card p-5 ${className}`}>
        <LoadingState className="py-6" spinnerClassName="w-6 h-6 text-primary-500 animate-spin" />
      </div>
    );
  }

  if (profiles.length === 0) {
    return (
      <div className={`surface-card p-5 md:p-6 ${className}`}>
        <h3 className="font-semibold text-stone-900 dark:text-stone-50 text-sm">能力画像</h3>
        <p className="mt-2 text-sm text-stone-500 dark:text-stone-400">暂无已评估能力记录</p>
      </div>
    );
  }

  return (
    <div className={`surface-card p-5 md:p-6 ${className}`}>
      <div className="flex items-center justify-between gap-3 mb-4">
        <h3 className="font-semibold text-stone-900 dark:text-stone-50 text-sm">能力画像</h3>
        <span className="text-xs text-stone-400">{profiles.length} 个能力原子</span>
      </div>
      <div className="divide-y divide-stone-100 dark:divide-stone-800">
        {profiles.map((item) => {
          const isWeakness = item.masteryLevel === 'WEAKNESS';
          const isDeveloping = item.masteryLevel === 'DEVELOPING';
          const isVerified = item.verificationState === 'VERIFIED';
          const statusLabel = isWeakness ? '薄弱' : isDeveloping ? '发展中' : '掌握';
          const statusClass = isWeakness
            ? 'bg-rose-50 text-rose-700 dark:bg-rose-950/50 dark:text-rose-300'
            : isDeveloping
              ? 'bg-amber-50 text-amber-700 dark:bg-amber-950/50 dark:text-amber-300'
              : 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-300';
          const StatusIcon = isWeakness ? TrendingDown : TrendingUp;
          return (
            <div
              key={item.capabilityAtomId ?? item.topic}
              className="py-3.5 first:pt-0 last:pb-0"
            >
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <p className="font-medium text-stone-800 dark:text-stone-100 text-sm">{item.topic}</p>
                  {item.capabilityAtomId && (
                    <p className="mt-0.5 text-[11px] text-stone-400 truncate">{item.capabilityAtomId}</p>
                  )}
                </div>
                <div className="flex items-center gap-1.5 shrink-0">
                  <span className={`inline-flex items-center gap-1 text-xs px-1.5 py-0.5 rounded ${statusClass}`}>
                    <StatusIcon className="w-3 h-3" /> {statusLabel}
                  </span>
                  <span className={`inline-flex items-center gap-1 text-xs px-1.5 py-0.5 rounded ${
                    isVerified
                      ? 'bg-sky-50 text-sky-700 dark:bg-sky-950/50 dark:text-sky-300'
                      : 'bg-stone-100 text-stone-500 dark:bg-stone-800 dark:text-stone-400'
                  }`}>
                    {isVerified ? <BadgeCheck className="w-3 h-3" /> : <CircleDashed className="w-3 h-3" />}
                    {isVerified ? '已验证' : '待复测'}
                  </span>
                </div>
              </div>
              {item.averageScore !== null && (
                <div className="mt-2 flex items-center gap-2">
                  <div className="h-1.5 flex-1 max-w-48 rounded bg-stone-100 dark:bg-stone-800 overflow-hidden">
                    <div
                      className={`h-full ${isWeakness ? 'bg-rose-500' : isDeveloping ? 'bg-amber-500' : 'bg-emerald-500'}`}
                      style={{width: `${Math.max(0, Math.min(100, item.averageScore))}%`}}
                    />
                  </div>
                  <span className="text-xs font-medium tabular-nums text-stone-600 dark:text-stone-300">
                    {item.averageScore}
                  </span>
                </div>
              )}
              <p className="mt-2 text-xs text-stone-600 dark:text-stone-400 line-clamp-3 leading-relaxed">
                {item.latestEvidence || '暂无证据摘要'}
              </p>
              <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-xs text-stone-400">
                <span>{item.sessionCount} 场</span>
                <span>{item.observationCount} 次观测</span>
                <span>薄弱 {item.weaknessCount}</span>
                <span>发展中 {item.developingCount}</span>
                <span>掌握 {item.strengthCount}</span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
