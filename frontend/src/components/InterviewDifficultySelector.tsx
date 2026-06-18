import { DIFFICULTY_OPTIONS, type Difficulty } from '../hooks/useInterviewConfig';

interface InterviewDifficultySelectorProps {
  value: Difficulty;
  onChange: (value: Difficulty) => void;
  compact?: boolean;
}

export default function InterviewDifficultySelector({
  value,
  onChange,
  compact = false,
}: InterviewDifficultySelectorProps) {
  return (
    <div>
      <label className="flex items-center gap-2 mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">
        难度
      </label>
      <div className={`grid grid-cols-3 ${compact ? 'gap-2' : 'gap-3'}`}>
        {DIFFICULTY_OPTIONS.map(opt => {
          const selected = value === opt.value;
          return (
            <button
              key={opt.value}
              onClick={() => onChange(opt.value)}
              className={`${compact ? 'py-2.5 px-3' : 'py-3 px-4'} rounded-xl border-2 transition-all duration-200 text-center
                ${selected
                  ? 'border-primary-500 bg-primary-50/80 dark:bg-primary-900/20'
                  : 'border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 hover:border-slate-300 dark:hover:border-slate-600'
                }`}
            >
              <p className={`text-sm font-semibold ${selected ? 'text-primary-700 dark:text-primary-300' : 'text-slate-700 dark:text-slate-300'}`}>
                {opt.label}
              </p>
              <p className={`${compact ? 'text-[11px]' : 'text-xs'} text-slate-400`}>{opt.desc}</p>
            </button>
          );
        })}
      </div>
    </div>
  );
}
