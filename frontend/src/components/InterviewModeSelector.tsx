import { FileText, Mic } from 'lucide-react';
import type { InterviewMode } from '../hooks/useInterviewConfig';

const MODE_OPTIONS = [
  {
    value: 'text' as const,
    label: '文字面试',
    icon: FileText,
    desc: {
      default: '推荐：更稳定，更适合系统化刷题与复盘',
      compact: '推荐：更稳定，更适合系统化练习',
    },
    recommended: true,
  },
  {
    value: 'voice' as const,
    label: '语音面试',
    icon: Mic,
    desc: {
      default: '实时语音对话，更偏临场模拟',
      compact: '实时语音对话，偏临场模拟',
    },
    recommended: false,
  },
] satisfies Array<{
  value: InterviewMode;
  label: string;
  icon: typeof FileText;
  desc: { default: string; compact: string };
  recommended: boolean;
}>;

interface InterviewModeSelectorProps {
  value: InterviewMode;
  onChange: (value: InterviewMode) => void;
  compact?: boolean;
}

export default function InterviewModeSelector({
  value,
  onChange,
  compact = false,
}: InterviewModeSelectorProps) {
  return (
    <div>
      <label className="flex items-center gap-2 mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">
        面试模式
      </label>
      <div className={`grid grid-cols-2 ${compact ? 'gap-2' : 'gap-3'}`}>
        {MODE_OPTIONS.map(opt => {
          const Icon = opt.icon;
          const selected = value === opt.value;
          return (
            <button
              key={opt.value}
              onClick={() => onChange(opt.value)}
              className={`flex items-center gap-3 ${compact ? 'p-3' : 'p-4'} rounded-xl border-2 transition-all duration-200 text-left
                ${selected
                  ? 'border-primary-500 bg-primary-50/80 dark:bg-primary-900/20'
                  : 'border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 hover:border-slate-300 dark:hover:border-slate-600'
                }`}
            >
              <Icon
                className={`${compact ? 'w-5 h-5' : 'w-6 h-6'} flex-shrink-0 ${selected ? 'text-primary-500' : 'text-slate-400'}`}
              />
              <div className="min-w-0">
                <p className={`font-semibold text-sm flex items-center gap-2 ${selected ? 'text-primary-700 dark:text-primary-300' : 'text-slate-900 dark:text-white'}`}>
                  <span>{opt.label}</span>
                  {opt.recommended && (
                    <span className="px-1.5 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300">
                      推荐
                    </span>
                  )}
                </p>
                <p className={`${compact ? 'text-[11px]' : 'text-xs'} text-slate-500 dark:text-slate-400`}>
                  {compact ? opt.desc.compact : opt.desc.default}
                </p>
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}
