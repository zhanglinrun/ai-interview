import {getScoreProgressColor} from '../utils/score';

interface ScoreProgressProps {
  score: number;
  maxScore?: number;
  colorClassName?: string;
  displayValue?: string | number;
  trackColorClassName?: string;
  widthClassName?: string;
  supportDarkMode?: boolean;
  valueClassName?: string;
}

export default function ScoreProgress({
  score,
  maxScore = 100,
  colorClassName,
  displayValue = score,
  trackColorClassName,
  widthClassName = 'w-16',
  supportDarkMode = true,
  valueClassName = 'font-bold text-slate-800 dark:text-white',
}: ScoreProgressProps) {
  const progress = maxScore === 100 ? score : Math.round((score / maxScore) * 100);
  const progressColor = colorClassName ?? getScoreProgressColor(score);
  const trackColor = trackColorClassName
    ?? (supportDarkMode ? 'bg-slate-100 dark:bg-slate-700' : 'bg-slate-100');
  const trackClassName = `${widthClassName} h-2 ${trackColor} rounded-full overflow-hidden`;

  return (
    <div className="flex items-center gap-3">
      <div className={trackClassName}>
        <div
          className={`h-full ${progressColor} rounded-full`}
          style={{width: `${progress}%`}}
        />
      </div>
      <span className={valueClassName}>{displayValue}</span>
    </div>
  );
}
