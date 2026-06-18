import {AlertCircle, CheckCircle, Clock, Loader2, RefreshCw} from 'lucide-react';
import type {AnalyzeStatus} from '../api/history';
import {
  isAnalyzeStatusCompleted,
  isAnalyzeStatusFailed,
  isAnalyzeStatusProcessing,
} from '../utils/analyzeStatus';

type AnalyzeStatusTextMode = 'short' | 'detail';
type StatusColor = 'blue' | 'green' | 'red' | 'yellow';

const ANALYZE_STATUS_TEXT = {
  COMPLETED: {
    short: '已完成',
    detail: '分析完成',
  },
  PROCESSING: {
    short: '分析中',
    detail: '分析中',
  },
  PENDING: {
    short: '待分析',
    detail: '等待分析',
  },
  FAILED: {
    short: '失败',
    detail: '分析失败',
  },
} satisfies Record<AnalyzeStatus, Record<AnalyzeStatusTextMode, string>>;

interface AnalyzeStatusBadgeProps {
  status?: AnalyzeStatus;
  hasScore?: boolean;
  textMode?: AnalyzeStatusTextMode;
  spinner?: 'loader' | 'refresh';
  darkMode?: boolean;
  textClassName?: string;
}

function iconColorClass(color: StatusColor, darkMode: boolean): string {
  if (!darkMode) {
    switch (color) {
      case 'blue':
        return 'text-blue-500';
      case 'green':
        return 'text-green-500';
      case 'red':
        return 'text-red-500';
      case 'yellow':
        return 'text-yellow-500';
    }
  }

  switch (color) {
    case 'blue':
      return 'text-blue-500 dark:text-blue-400';
    case 'green':
      return 'text-green-500 dark:text-green-400';
    case 'red':
      return 'text-red-500 dark:text-red-400';
    case 'yellow':
      return 'text-yellow-500 dark:text-yellow-400';
  }
}

function getAnalyzeStatusText(
  status?: AnalyzeStatus,
  hasScore = false,
  mode: AnalyzeStatusTextMode = 'short'
): string {
  if (status === undefined) {
    return hasScore ? '已完成' : '待分析';
  }
  return ANALYZE_STATUS_TEXT[status][mode];
}

function AnalyzeStatusIcon({
  status,
  hasScore = false,
  spinner = 'loader',
  darkMode = false,
}: Pick<AnalyzeStatusBadgeProps, 'status' | 'hasScore' | 'spinner' | 'darkMode'>) {
  if (status === undefined) {
    if (hasScore) {
      return <CheckCircle className={`w-4 h-4 ${iconColorClass('green', darkMode)}`} />;
    }
    return <Clock className={`w-4 h-4 ${iconColorClass('yellow', darkMode)}`} />;
  }

  if (isAnalyzeStatusFailed(status)) {
    return <AlertCircle className={`w-4 h-4 ${iconColorClass('red', darkMode)}`} />;
  }
  if (isAnalyzeStatusProcessing(status)) {
    const className = `w-4 h-4 ${iconColorClass('blue', darkMode)} animate-spin`;
    return spinner === 'refresh' ? <RefreshCw className={className} /> : <Loader2 className={className} />;
  }
  if (isAnalyzeStatusCompleted(status)) {
    return <CheckCircle className={`w-4 h-4 ${iconColorClass('green', darkMode)}`} />;
  }
  return <Clock className={`w-4 h-4 ${iconColorClass('yellow', darkMode)}`} />;
}

export default function AnalyzeStatusBadge({
  status,
  hasScore = false,
  textMode = 'short',
  spinner = 'loader',
  darkMode = false,
  textClassName = 'text-sm text-slate-600',
}: AnalyzeStatusBadgeProps) {
  return (
    <div className="flex items-center gap-2">
      <AnalyzeStatusIcon
        status={status}
        hasScore={hasScore}
        spinner={spinner}
        darkMode={darkMode}
      />
      <span className={textClassName}>
        {getAnalyzeStatusText(status, hasScore, textMode)}
      </span>
    </div>
  );
}
