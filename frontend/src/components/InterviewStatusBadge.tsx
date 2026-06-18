import {AlertCircle, CheckCircle, Clock, PlayCircle, RefreshCw} from 'lucide-react';
import {
  getInterviewStatusText,
  isActiveInterviewStatus,
  isEvaluationCompleted,
  isEvaluationFailed,
  isEvaluationProcessing,
} from '../utils/interviewStatus';
import type {EvaluateStatus} from '../api/history';

interface InterviewStatusBadgeProps {
  status: string;
  evaluateStatus?: EvaluateStatus | null;
  className?: string;
  iconClassName?: string;
  textClassName?: string;
}

export default function InterviewStatusBadge({
  status,
  evaluateStatus,
  className = 'flex items-center gap-2',
  iconClassName,
  textClassName = 'text-sm text-slate-600 dark:text-slate-300',
}: InterviewStatusBadgeProps) {
  const iconClass = (defaultClassName: string) => iconClassName ?? defaultClassName;

  let icon = <Clock className={iconClass('w-4 h-4 text-yellow-500 dark:text-yellow-400')} />;

  if (isEvaluationFailed(evaluateStatus)) {
    icon = <AlertCircle className={iconClass('w-4 h-4 text-red-500 dark:text-red-400')} />;
  } else if (isEvaluationProcessing(evaluateStatus)) {
    icon = <RefreshCw className={iconClass('w-4 h-4 text-blue-500 dark:text-blue-400 animate-spin')} />;
  } else if (isEvaluationCompleted(evaluateStatus, status)) {
    icon = <CheckCircle className={iconClass('w-4 h-4 text-green-500 dark:text-green-400')} />;
  } else if (isActiveInterviewStatus(status)) {
    icon = <PlayCircle className={iconClass('w-4 h-4 text-blue-500 dark:text-blue-400')} />;
  }

  return (
    <div className={className}>
      {icon}
      <span className={textClassName}>
        {getInterviewStatusText(status, evaluateStatus)}
      </span>
    </div>
  );
}
