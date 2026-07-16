import ScoreProgress from './ScoreProgress';

interface ScoreProgressBarProps {
  label: string;
  score: number;
  maxScore: number;
  color?: string;
  className?: string;
}

/**
 * 分数进度条组件
 */
export default function ScoreProgressBar({
  label,
  score,
  maxScore,
  color = 'bg-primary-500',
  className = ''
}: ScoreProgressBarProps) {
  return (
      <div className={`bg-slate-50 dark:bg-slate-700/50 rounded-lg p-3 ${className}`}>
          <div className="text-xs text-slate-500 dark:text-slate-400 mb-1">{label}</div>
      <ScoreProgress
        score={score}
        maxScore={maxScore}
        colorClassName={color}
        displayValue={`${score}/${maxScore}`}
        trackColorClassName="bg-slate-200 dark:bg-slate-600"
        widthClassName="flex-1"
        valueClassName="text-sm font-semibold text-slate-700 dark:text-slate-300 w-8 text-right"
      />
    </div>
  );
}
