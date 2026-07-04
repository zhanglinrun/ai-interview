import {useCallback, useEffect, useState} from 'react';
import {TrendingDown, TrendingUp} from 'lucide-react';
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
        <p className="mt-2 text-sm text-stone-500 dark:text-stone-400 leading-relaxed">
          完成模拟面试并生成评估后，系统会汇总各主题的薄弱点与掌握点，用于后续自适应出题。
        </p>
      </div>
    );
  }

  return (
    <div className={`surface-card p-5 md:p-6 ${className}`}>
      <div className="flex items-center justify-between gap-3 mb-4">
        <h3 className="font-semibold text-stone-900 dark:text-stone-50 text-sm">能力画像</h3>
        <span className="text-xs text-stone-400">{profiles.length} 个主题</span>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        {profiles.map((item) => {
          const isWeakness = item.latestKind === 'weakness';
          return (
            <div
              key={item.topic}
              className="rounded-xl border border-stone-200/80 dark:border-stone-800 p-3.5 bg-stone-50/50 dark:bg-stone-900/30"
            >
              <div className="flex items-start justify-between gap-2">
                <p className="font-medium text-stone-800 dark:text-stone-100 text-sm">{item.topic}</p>
                <span className={`inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-md shrink-0 ${
                  isWeakness
                    ? 'bg-amber-100 text-amber-800 dark:bg-amber-950/50 dark:text-amber-300'
                    : 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950/50 dark:text-emerald-300'
                }`}>
                  {isWeakness ? <TrendingDown className="w-3 h-3" /> : <TrendingUp className="w-3 h-3" />}
                  {isWeakness ? '薄弱' : '掌握'}
                </span>
              </div>
              <p className="mt-2 text-xs text-stone-600 dark:text-stone-400 line-clamp-3 leading-relaxed">
                {item.latestEvidence || '暂无证据摘要'}
              </p>
              <div className="mt-2.5 flex gap-3 text-xs text-stone-400">
                <span>薄弱 {item.weaknessCount}</span>
                <span>掌握 {item.strengthCount}</span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
